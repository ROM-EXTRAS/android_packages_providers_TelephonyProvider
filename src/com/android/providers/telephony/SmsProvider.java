/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.telephony;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Contacts;
import android.provider.Telephony;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.util.TelephonyUtils;

import java.util.HashMap;
import java.util.List;

public class SmsProvider extends ContentProvider {
    /* No response constant from SmsResponse */
    static final int NO_ERROR_CODE = -1;

    private static final Uri NOTIFICATION_URI = Uri.parse("content://sms");
    private static final Uri ICC_URI = Uri.parse("content://sms/icc");
    private static final Uri ICC_SUBID_URI = Uri.parse("content://sms/icc_subId");
    static final String TABLE_SMS = "sms";
    static final String TABLE_RAW = "raw";
    static final String TABLE_ATTACHMENTS = "attachments";
    static final String TABLE_CANONICAL_ADDRESSES = "canonical_addresses";
    static final String TABLE_SR_PENDING = "sr_pending";
    private static final String TABLE_WORDS = "words";
    static final String VIEW_SMS_RESTRICTED = "sms_restricted";

    private static final Integer ONE = Integer.valueOf(1);

    private static final String[] CONTACT_QUERY_PROJECTION =
            new String[] { Contacts.Phones.PERSON_ID };
    private static final int PERSON_ID_COLUMN = 0;

    /** Delete any raw messages or message segments marked deleted that are older than an hour. */
    static final long RAW_MESSAGE_EXPIRE_AGE_MS = (long) (60 * 60 * 1000);

    /**
     * These are the columns that are available when reading SMS
     * messages from the ICC.  Columns whose names begin with "is_"
     * have either "true" or "false" as their values.
     */
    private final static String[] ICC_COLUMNS = new String[] {
        // N.B.: These columns must appear in the same order as the
        // calls to add appear in convertIccToSms.
        "service_center_address",       // getServiceCenterAddress
        "address",                      // getDisplayOriginatingAddress or getRecipientAddress
        "message_class",                // getMessageClass
        "body",                         // getDisplayMessageBody
        "date",                         // getTimestampMillis
        "status",                       // getStatusOnIcc
        "index_on_icc",                 // getIndexOnIcc (1-based index)
        "is_status_report",             // isStatusReportMessage
        "transport_type",               // Always "sms".
        "type",                         // depend on getStatusOnIcc
        "locked",                       // Always 0 (false).
        "error_code",                   // Always -1 (NO_ERROR_CODE), previously it was 0 always.
        "_id"
    };

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        // So we have two database files. One in de, one in ce. Here only "raw" table is in
        // mDeOpenHelper, other tables are all in mCeOpenHelper.
        mDeOpenHelper = MmsSmsDatabaseHelper.getInstanceForDe(getContext());
        mCeOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        TelephonyBackupAgent.DeferredSmsMmsRestoreService.startIfFilesExist(getContext());

        // Creating intent broadcast receiver for user actions like Intent.ACTION_USER_REMOVED,
        // where we would need to remove SMS related to removed user.
        IntentFilter userIntentFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiver(mUserIntentReceiver, userIntentFilter,
                Context.RECEIVER_NOT_EXPORTED);

        return true;
    }

    private boolean hasCalling() {
        return getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_CALLING);
    }

    /**
     * Return the proper view of "sms" table for the current access status.
     *
     * @param accessRestricted If the access is restricted
     * @return the table/view name of the "sms" data
     */
    public static String getSmsTable(boolean accessRestricted) {
        return accessRestricted ? VIEW_SMS_RESTRICTED : TABLE_SMS;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        String callingPackage = getCallingPackage();
        final int callingUid = 1001;
        final UserHandle callerUserHandle = UserHandle.getUserHandleForUid(1001);
        Log.e(TAG, "query: " + callingPackage);

        // First check if a restricted view of the "sms" table should be used based on the
        // caller's identity. Only system, phone or the default sms app can have full access
        // of sms data. For other apps, we present a restricted view which only contains sent
        // or received messages.
        final boolean accessRestricted = ProviderUtil.isAccessRestricted(
                getContext(), getCallingPackage(), callingUid);
        final String smsTable = getSmsTable(accessRestricted);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        // If access is restricted, we don't allow subqueries in the query.
        if (accessRestricted) {
            try {
                SqlQueryChecker.checkQueryParametersForSubqueries(projectionIn, selection, sort);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Query rejected: " + e.getMessage());
                return null;
            }
        }

        Cursor emptyCursor = new MatrixCursor((projectionIn == null) ?
                (new String[] {}) : projectionIn);

        // Generate the body of the query.
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = getReadableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callingPackage + ";SmsProvider.query;" + url, true);
        }
        switch (match) {
            case SMS_ALL:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_ALL, smsTable);
                break;

            case SMS_UNDELIVERED:
                constructQueryForUndelivered(qb, smsTable);
                break;

            case SMS_FAILED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_FAILED, smsTable);
                break;

            case SMS_QUEUED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_QUEUED, smsTable);
                break;

            case SMS_INBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_INBOX, smsTable);
                break;

            case SMS_SENT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_SENT, smsTable);
                break;

            case SMS_DRAFT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_DRAFT, smsTable);
                break;

            case SMS_OUTBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_OUTBOX, smsTable);
                break;

            case SMS_ALL_ID:
                qb.setTables(smsTable);
                qb.appendWhere("(_id = " + url.getPathSegments().get(0) + ")");
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                qb.setTables(smsTable);
                qb.appendWhere("(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.d(TAG, "query conversations: threadID=" + threadID);
                    }
                }
                catch (Exception ex) {
                    Log.e(TAG,
                          "Bad conversation thread id: "
                          + url.getPathSegments().get(1));
                    return null;
                }

                qb.setTables(smsTable);
                qb.appendWhere("thread_id = " + threadID);
                break;

            case SMS_CONVERSATIONS:
                qb.setTables(smsTable + ", "
                        + "(SELECT thread_id AS group_thread_id, "
                        + "MAX(date) AS group_date, "
                        + "COUNT(*) AS msg_count "
                        + "FROM " + smsTable + " "
                        + "GROUP BY thread_id) AS groups");
                qb.appendWhere(smsTable + ".thread_id=groups.group_thread_id"
                        + " AND " + smsTable + ".date=groups.group_date");
                final HashMap<String, String> projectionMap = new HashMap<>();
                projectionMap.put(Sms.Conversations.SNIPPET,
                        smsTable + ".body AS snippet");
                projectionMap.put(Sms.Conversations.THREAD_ID,
                        smsTable + ".thread_id AS thread_id");
                projectionMap.put(Sms.Conversations.MESSAGE_COUNT,
                        "groups.msg_count AS msg_count");
                projectionMap.put("delta", null);
                qb.setProjectionMap(projectionMap);
                break;

            case SMS_RAW_MESSAGE:
                // before querying purge old entries with deleted = 1
                purgeDeletedMessagesInRawTable(db);
                qb.setTables("raw");
                break;

            case SMS_STATUS_PENDING:
                qb.setTables("sr_pending");
                break;

            case SMS_ATTACHMENT:
                qb.setTables("attachments");
                break;

            case SMS_ATTACHMENT_ID:
                qb.setTables("attachments");
                qb.appendWhere(
                        "(sms_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_QUERY_THREAD_ID:
                qb.setTables("canonical_addresses");
                if (projectionIn == null) {
                    projectionIn = sIDProjection;
                }
                break;

            case SMS_STATUS_ID:
                qb.setTables(smsTable);
                qb.appendWhere("(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID: {
                int subId;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return empty cursor.
                    return emptyCursor;
                }

                Cursor ret = getAllMessagesFromIcc(subId);
                ret.setNotificationUri(getContext().getContentResolver(),
                        match == SMS_ALL_ICC ? ICC_URI : ICC_SUBID_URI);
                return ret;
            }

            case SMS_ICC:
            case SMS_ICC_SUBID: {
                int subId;
                int messageIndex;
                try {
                    if (match == SMS_ICC) {
                        subId = SmsManager.getDefaultSmsSubscriptionId();
                        messageIndex = Integer.parseInt(url.getPathSegments().get(1));
                    } else {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                        messageIndex = Integer.parseInt(url.getPathSegments().get(2));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return empty cursor.
                    return emptyCursor;
                }

                Cursor ret = getSingleMessageFromIcc(subId, messageIndex);
                ret.setNotificationUri(getContext().getContentResolver(),
                        match == SMS_ICC ? ICC_URI : ICC_SUBID_URI);
                return ret;
            }

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        final long token = Binder.clearCallingIdentity();
        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle);
            if (hasCalling() && qb.getTables().equals(smsTable)) {
                selectionByEmergencyNumbers = ProviderUtil
                        .getSelectionByEmergencyNumbers(getContext());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (qb.getTables().equals(smsTable)) {
            if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
                // No subscriptions associated with user
                // and no emergency numbers return empty cursor.
                return emptyCursor;
            }
        } else {
            if (selectionBySubIds == null) {
                // No subscriptions associated with user return empty cursor.
                return emptyCursor;
            }
        }

        String filter = "";
        if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }
        selection = DatabaseUtils.concatenateWhere(selection, filter);

        String orderBy = null;

        if (!TextUtils.isEmpty(sort)) {
            orderBy = sort;
        } else if (qb.getTables().equals(smsTable)) {
            orderBy = Sms.DEFAULT_SORT_ORDER;
        }

        Cursor ret = qb.query(db, projectionIn, selection, selectionArgs,
                              null, null, orderBy);
        // TODO: Since the URLs are a mess, always use content://sms
        ret.setNotificationUri(getContext().getContentResolver(),
                NOTIFICATION_URI);
        return ret;
    }

    private void purgeDeletedMessagesInRawTable(SQLiteDatabase db) {
        long oldTimestamp = System.currentTimeMillis() - RAW_MESSAGE_EXPIRE_AGE_MS;
        int num = db.delete(TABLE_RAW, "deleted = 1 AND date < " + oldTimestamp, null);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "purgeDeletedMessagesInRawTable: num rows older than " + oldTimestamp +
                    " purged: " + num);
        }
    }

    private SQLiteOpenHelper getDBOpenHelper(int match) {
        // Raw table is stored on de database. Other tables are stored in ce database.
        if (match == SMS_RAW_MESSAGE || match == SMS_RAW_MESSAGE_PERMANENT_DELETE) {
            return mDeOpenHelper;
        }
        return mCeOpenHelper;
    }

    private Object[] convertIccToSms(SmsMessage message, int id) {
        int statusOnIcc = message.getStatusOnIcc();
        int type = Sms.MESSAGE_TYPE_ALL;
        switch (statusOnIcc) {
            case SmsManager.STATUS_ON_ICC_READ:
            case SmsManager.STATUS_ON_ICC_UNREAD:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;
            case SmsManager.STATUS_ON_ICC_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;
        }

        String address = (type == Sms.MESSAGE_TYPE_INBOX)
                ? message.getDisplayOriginatingAddress()
                : message.getRecipientAddress();

        int index = message.getIndexOnIcc();
        if (address == null) {
            // The status byte of an EF_SMS record may not be correct. try to read other address
            // type again.
            Log.e(TAG, "convertIccToSms: EF_SMS(" + index + ")=> address=null, type=" + type
                    + ", status=" + statusOnIcc + "(may not be correct). fallback to other type.");
            address = (type == Sms.MESSAGE_TYPE_INBOX)
                    ? message.getRecipientAddress()
                    : message.getDisplayOriginatingAddress();

            if (address != null) {
                // Rely on actual PDU(address) to set type again.
                type = (type == Sms.MESSAGE_TYPE_INBOX)
                        ? Sms.MESSAGE_TYPE_SENT
                        : Sms.MESSAGE_TYPE_INBOX;
                Log.d(TAG, "convertIccToSms: new type=" + type + ", address=xxxxxx");
            } else {
                Log.e(TAG, "convertIccToSms: no change");
            }
        }

        // N.B.: These calls must appear in the same order as the
        // columns appear in ICC_COLUMNS.
        Object[] row = new Object[13];
        row[0] = message.getServiceCenterAddress();
        row[1] = address;
        row[2] = String.valueOf(message.getMessageClass());
        row[3] = message.getDisplayMessageBody();
        row[4] = message.getTimestampMillis();
        row[5] = statusOnIcc;
        row[6] = index;
        row[7] = message.isStatusReportMessage();
        row[8] = "sms";
        row[9] = type;
        row[10] = 0;      // locked
        row[11] = NO_ERROR_CODE;
        row[12] = id;
        return row;
    }

    /**
     * Gets single message from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param messageIndex the message index of the messaage in the ICC (1-based index).
     * @return a cursor containing just one message from the ICC for the subscription ID.
     */
    private Cursor getSingleMessageFromIcc(int subId, int messageIndex) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
        List<SmsMessage> messages;

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            // getMessagesFromIcc() returns a zero-based list of valid messages in the ICC.
            messages = smsManager.getMessagesFromIcc();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final int count = messages.size();
        for (int i = 0; i < count; i++) {
            SmsMessage message = messages.get(i);
            if (message != null && message.getIndexOnIcc() == messageIndex) {
                MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, 1);
                cursor.addRow(convertIccToSms(message, 0));
                return cursor;
            }
        }

        throw new IllegalArgumentException(
                "No message in index " + messageIndex + " for subId " + subId);
    }

    /**
     * Gets all the messages in the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @return a cursor listing all the message in the ICC for the subscription ID.
     */
    private Cursor getAllMessagesFromIcc(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
        List<SmsMessage> messages;

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call
        long token = Binder.clearCallingIdentity();
        try {
            // getMessagesFromIcc() returns a zero-based list of valid messages in the ICC.
            messages = smsManager.getMessagesFromIcc();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final int count = messages.size();
        MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, count);
        for (int i = 0; i < count; i++) {
            SmsMessage message = messages.get(i);
            if (message != null) {
                cursor.addRow(convertIccToSms(message, i));
            }
        }
        return cursor;
    }

    private void constructQueryForBox(SQLiteQueryBuilder qb, int type, String smsTable) {
        qb.setTables(smsTable);

        if (type != Sms.MESSAGE_TYPE_ALL) {
            qb.appendWhere("type=" + type);
        }
    }

    private void constructQueryForUndelivered(SQLiteQueryBuilder qb, String smsTable) {
        qb.setTables(smsTable);

        qb.appendWhere("(type=" + Sms.MESSAGE_TYPE_OUTBOX +
                       " OR type=" + Sms.MESSAGE_TYPE_FAILED +
                       " OR type=" + Sms.MESSAGE_TYPE_QUEUED + ")");
    }

    @Override
    public String getType(Uri url) {
        switch (url.getPathSegments().size()) {
        case 0:
            return VND_ANDROID_DIR_SMS;
            case 1:
                try {
                    Integer.parseInt(url.getPathSegments().get(0));
                    return VND_ANDROID_SMS;
                } catch (NumberFormatException ex) {
                    return VND_ANDROID_DIR_SMS;
                }
            case 2:
                // TODO: What about "threadID"?
                if (url.getPathSegments().get(0).equals("conversations")) {
                    return VND_ANDROID_SMSCHAT;
                } else {
                    return VND_ANDROID_SMS;
                }
        }
        return null;
    }

    @Override
    public int bulkInsert(@NonNull Uri url, @NonNull ContentValues[] values) {
        final int callerUid = 1001;
        final UserHandle callerUserHandle = UserHandle.getUserHandleForUid(1001);
        final String callerPkg = getCallingPackage();
        long token = Binder.clearCallingIdentity();
        Log.e(TAG, "bulkInsert: " + callerPkg);
        try {
            int messagesInserted = 0;
            for (ContentValues initialValues : values) {
                Uri insertUri = insertInner(url, initialValues, callerUid, callerPkg,
                        callerUserHandle);
                if (insertUri != null) {
                    messagesInserted++;
                }
            }

            // The raw table is used by the telephony layer for storing an sms before
            // sending out a notification that an sms has arrived. We don't want to notify
            // the default sms app of changes to this table.
            final boolean notifyIfNotDefault = sURLMatcher.match(url) != SMS_RAW_MESSAGE;
            notifyChange(notifyIfNotDefault, url, callerPkg);
            return messagesInserted;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        final int callerUid = 1001;
        final UserHandle callerUserHandle = UserHandle.getUserHandleForUid(1001);
        final String callerPkg = getCallingPackage();
        long token = Binder.clearCallingIdentity();
        Log.e(TAG, "bulkInsert: " + callerPkg);
        try {
            Uri insertUri = insertInner(url, initialValues, callerUid, callerPkg, callerUserHandle);

            // Skip notifyChange() if insertUri is null
            if (insertUri != null) {
                int match = sURLMatcher.match(url);
                // The raw table is used by the telephony layer for storing an sms before sending
                // out a notification that an sms has arrived. We don't want to notify the default
                // sms app of changes to this table.
                final boolean notifyIfNotDefault = match != SMS_RAW_MESSAGE;
                notifyChange(notifyIfNotDefault, insertUri, callerPkg);
            }
            return insertUri;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Uri insertInner(Uri url, ContentValues initialValues, int callerUid, String callerPkg,
            UserHandle callerUserHandle) {
        ContentValues values;
        long rowID;
        int type = Sms.MESSAGE_TYPE_ALL;

        int match = sURLMatcher.match(url);
        String table = TABLE_SMS;

        switch (match) {
            case SMS_ALL:
                Integer typeObj = initialValues.getAsInteger(Sms.TYPE);
                if (typeObj != null) {
                    type = typeObj.intValue();
                } else {
                    // default to inbox
                    type = Sms.MESSAGE_TYPE_INBOX;
                }
                break;

            case SMS_INBOX:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;

            case SMS_FAILED:
                type = Sms.MESSAGE_TYPE_FAILED;
                break;

            case SMS_QUEUED:
                type = Sms.MESSAGE_TYPE_QUEUED;
                break;

            case SMS_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;

            case SMS_DRAFT:
                type = Sms.MESSAGE_TYPE_DRAFT;
                break;

            case SMS_OUTBOX:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;

            case SMS_RAW_MESSAGE:
                table = "raw";
                break;

            case SMS_STATUS_PENDING:
                table = "sr_pending";
                break;

            case SMS_ATTACHMENT:
                table = "attachments";
                break;

            case SMS_NEW_THREAD_ID:
                table = "canonical_addresses";
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID:
                int subId;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Wrong path segements for SMS_ALL_ICC_SUBID, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(), subId,
                    callerUserHandle)) {
                    TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(),
                        subId, callerUid, callerPkg);
                    return null;
                }

                if (initialValues == null) {
                    throw new IllegalArgumentException("ContentValues is null");
                }

                String scAddress = initialValues.getAsString(Sms.SERVICE_CENTER);
                String address = initialValues.getAsString(Sms.ADDRESS);
                String message = initialValues.getAsString(Sms.BODY);
                boolean isRead = true;
                Integer obj = initialValues.getAsInteger(Sms.TYPE);

                if (obj == null || address == null || message == null) {
                    throw new IllegalArgumentException("Missing SMS data");
                }

                type = obj.intValue();
                if (!isSupportedType(type)) {
                    throw new IllegalArgumentException("Unsupported message type= " + type);
                }
                obj = initialValues.getAsInteger(Sms.READ); // 0: Unread, 1: Read
                if (obj != null && obj.intValue() == 0) {
                    isRead = false;
                }

                Long date = initialValues.getAsLong(Sms.DATE);
                return insertMessageToIcc(subId, scAddress, address, message, type, isRead,
                        date != null ? date : 0) ? url : null;

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        SQLiteDatabase db = getWritableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callerPkg + ";SmsProvider.insert;" + url, false);
        }

        if (table.equals(TABLE_SMS)) {
            boolean addDate = false;
            boolean addType = false;

            // Make sure that the date and type are set
            if (initialValues == null) {
                values = new ContentValues(1);
                addDate = true;
                addType = true;
            } else {
                values = new ContentValues(initialValues);

                if (!initialValues.containsKey(Sms.DATE)) {
                    addDate = true;
                }

                if (!initialValues.containsKey(Sms.TYPE)) {
                    addType = true;
                }
            }

            if (addDate) {
                values.put(Sms.DATE, new Long(System.currentTimeMillis()));
            }

            if (addType && (type != Sms.MESSAGE_TYPE_ALL)) {
                values.put(Sms.TYPE, Integer.valueOf(type));
            }

            // thread_id
            Long threadId = values.getAsLong(Sms.THREAD_ID);
            String address = values.getAsString(Sms.ADDRESS);

            if (((threadId == null) || (threadId == 0)) && (!TextUtils.isEmpty(address))) {
                values.put(Sms.THREAD_ID, Threads.getOrCreateThreadId(
                                   getContext(), address));
            }

            // If this message is going in as a draft, it should replace any
            // other draft messages in the thread.  Just delete all draft
            // messages with this thread ID.  We could add an OR REPLACE to
            // the insert below, but we'd have to query to find the old _id
            // to produce a conflict anyway.
            if (values.getAsInteger(Sms.TYPE) == Sms.MESSAGE_TYPE_DRAFT) {
                db.delete(TABLE_SMS, "thread_id=? AND type=?",
                        new String[] { values.getAsString(Sms.THREAD_ID),
                                       Integer.toString(Sms.MESSAGE_TYPE_DRAFT) });
            }

            if (type == Sms.MESSAGE_TYPE_INBOX) {
                // Look up the person if not already filled in.
                if ((values.getAsLong(Sms.PERSON) == null) && (!TextUtils.isEmpty(address))) {
                    Cursor cursor = null;
                    Uri uri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL,
                            Uri.encode(address));
                    try {
                        cursor = getContext().getContentResolver().query(
                                uri,
                                CONTACT_QUERY_PROJECTION,
                                null, null, null);

                        if (cursor != null && cursor.moveToFirst()) {
                            Long id = Long.valueOf(cursor.getLong(PERSON_ID_COLUMN));
                            values.put(Sms.PERSON, id);
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "insert: query contact uri " + uri + " caught ", ex);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                // Mark all non-inbox messages read.
                values.put(Sms.READ, ONE);
            }
            if (ProviderUtil.shouldSetCreator(values, callerUid)) {
                // Only SYSTEM or PHONE can set CREATOR
                // If caller is not SYSTEM or PHONE, or SYSTEM or PHONE does not set CREATOR
                // set CREATOR using the truth on caller.
                // Note: Inferring package name from UID may include unrelated package names
                values.put(Sms.CREATOR, callerPkg);
            }
        } else {
            if (initialValues == null) {
                values = new ContentValues(1);
            } else {
                values = initialValues;
            }
        }

        // Insert subId value
        int subId;
        if (values.containsKey(Telephony.Sms.SUBSCRIPTION_ID)) {
            subId = values.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID);
        } else {
            // TODO (b/256992531): Currently, one sim card is set as default sms subId in work
            //  profile. Default sms subId should be updated based on user pref.
            subId = SmsManager.getDefaultSmsSubscriptionId();
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
            }
        }


        if (table.equals(TABLE_SMS)) {
            // Get destination address from values
            String address = "";
            if (values.containsKey(Sms.ADDRESS)) {
                address = values.getAsString(Sms.ADDRESS);
            }

            if (ProviderUtil.sFeatureFlag.rejectBadSubIdInteraction()) {
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        && !TelephonyPermissions.checkSubscriptionAssociatedWithUser(getContext(),
                        subId, callerUserHandle, address)) {
                    TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(),
                            subId, callerUid, callerPkg);
                    return null;
                }
            } else if (!TelephonyPermissions.checkSubscriptionAssociatedWithUser(getContext(),
                    subId, callerUserHandle, address)) {
                TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(), subId,
                        callerUid, callerPkg);
                return null;
            }
        }

        rowID = db.insert(table, "body", values);

        // Don't use a trigger for updating the words table because of a bug
        // in FTS3.  The bug is such that the call to get the last inserted
        // row is incorrect.
        if (table == TABLE_SMS) {
            // Update the words table with a corresponding row.  The words table
            // allows us to search for words quickly, without scanning the whole
            // table;
            ContentValues cv = new ContentValues();
            cv.put(Telephony.MmsSms.WordsTable.ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.INDEXED_TEXT, values.getAsString("body"));
            cv.put(Telephony.MmsSms.WordsTable.SOURCE_ROW_ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.TABLE_ID, 1);
            cv.put(MmsSms.WordsTable.SUBSCRIPTION_ID, subId);
            db.insert(TABLE_WORDS, Telephony.MmsSms.WordsTable.INDEXED_TEXT, cv);
        }
        if (rowID > 0) {
            Uri uri = null;
            if (table == TABLE_SMS) {
                uri = Uri.withAppendedPath(Sms.CONTENT_URI, String.valueOf(rowID));
            } else {
                uri = Uri.withAppendedPath(url, String.valueOf(rowID));
            }
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "insert " + uri + " succeeded");
            }
            return uri;
        } else {
            Log.e(TAG, "insert: failed!");
            if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
                ((MmsSmsDatabaseHelper) sqLiteOpenHelper).printDatabaseOpeningDebugLog();
            }
        }

        return null;
    }

    private boolean isSupportedType(int messageType) {
        return (messageType == Sms.MESSAGE_TYPE_INBOX)
                || (messageType == Sms.MESSAGE_TYPE_OUTBOX)
                || (messageType == Sms.MESSAGE_TYPE_SENT);
    }

    private int getMessageStatusForIcc(int messageType, boolean isRead) {
        if (messageType == Sms.MESSAGE_TYPE_SENT) {
            return SmsManager.STATUS_ON_ICC_SENT;
        } else if (messageType == Sms.MESSAGE_TYPE_OUTBOX) {
            return SmsManager.STATUS_ON_ICC_UNSENT;
        } else { // Sms.MESSAGE_BOX_INBOX
            if (isRead) {
                return SmsManager.STATUS_ON_ICC_READ;
            } else {
                return SmsManager.STATUS_ON_ICC_UNREAD;
            }
        }
    }

    /**
     * Inserts new message to the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param scAddress the SMSC for this message.
     * @param address destination or originating address.
     * @param message the message text.
     * @param messageType type of the message.
     * @param isRead ture if the message has been read. Otherwise false.
     * @param date the date the message was received.
     * @return true for succeess. Otherwise false.
     */
    private boolean insertMessageToIcc(int subId, String scAddress, String address, String message,
            int messageType, boolean isRead, long date) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        int status = getMessageStatusForIcc(messageType, isRead);
        SmsMessage.SubmitPdu smsPdu =
                SmsMessage.getSmsPdu(subId, status, scAddress, address, message, date);

        if (smsPdu == null) {
            throw new IllegalArgumentException("Failed to create SMS PDU");
        }

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            return smsManager.copyMessageToIcc(
                    smsPdu.encodedScAddress, smsPdu.encodedMessage, status);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        final UserHandle callerUserHandle = UserHandle.getUserHandleForUid(1001);
        final int callerUid = 1001;
        final long token = Binder.clearCallingIdentity();

        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle);
            selectionByEmergencyNumbers = ProviderUtil
                    .getSelectionByEmergencyNumbers(getContext());
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        String filter = "";
        if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
            // No subscriptions associated with user and no emergency numbers
            filter = null;
        } else if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }

        int count;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = getWritableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    getCallingPackage() + ";SmsProvider.delete;" + url, false);
        }
        boolean notifyIfNotDefault = true;
        switch (match) {
            case SMS_ALL:
                if (filter == null) {
                    // No subscriptions associated with user and no emergency numbers, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, filter);
                count = db.delete(TABLE_SMS, where, whereArgs);
                if (count != 0) {
                    // Don't update threads unless something changed.
                    MmsSmsDatabaseHelper.updateThreads(db, where, whereArgs);
                }
                break;

            case SMS_ALL_ID:
                try {
                    int message_id = Integer.parseInt(url.getPathSegments().get(0));
                    count = MmsSmsDatabaseHelper.deleteOneSms(db, message_id);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Bad message id: " + url.getPathSegments().get(0));
                }
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(
                            "Bad conversation thread id: "
                            + url.getPathSegments().get(1));
                }

                // delete the messages from the sms table
                where = DatabaseUtils.concatenateWhere("thread_id=" + threadID, where);
                if (filter == null) {
                    // No subscriptions associated with user and no emergency numbers, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, filter);
                count = db.delete(TABLE_SMS, where, whereArgs);
                MmsSmsDatabaseHelper.updateThread(db, threadID);
                break;

            case SMS_RAW_MESSAGE:
                ContentValues cv = new ContentValues();
                cv.put("deleted", 1);
                count = db.update(TABLE_RAW, cv, where, whereArgs);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "delete: num rows marked deleted in raw table: " + count);
                }
                notifyIfNotDefault = false;
                break;

            case SMS_RAW_MESSAGE_PERMANENT_DELETE:
                count = db.delete(TABLE_RAW, where, whereArgs);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "delete: num rows permanently deleted in raw table: " + count);
                }
                notifyIfNotDefault = false;
                break;

            case SMS_STATUS_PENDING:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, selectionBySubIds);
                count = db.delete("sr_pending", where, whereArgs);
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID: {
                int subId;
                int deletedCnt;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return 0.
                    return 0;
                }

                deletedCnt = deleteAllMessagesFromIcc(subId);
                // Notify changes even failure case since there might be some changes should be
                // known.
                getContext()
                        .getContentResolver()
                        .notifyChange(
                                match == SMS_ALL_ICC ? ICC_URI : ICC_SUBID_URI,
                                null,
                                true,
                                UserHandle.USER_ALL);
                return deletedCnt;
            }

            case SMS_ICC:
            case SMS_ICC_SUBID: {
                int subId;
                int messageIndex;
                boolean success;
                try {
                    if (match == SMS_ICC) {
                        subId = SmsManager.getDefaultSmsSubscriptionId();
                        messageIndex = Integer.parseInt(url.getPathSegments().get(1));
                    } else {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                        messageIndex = Integer.parseInt(url.getPathSegments().get(2));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return 0.
                    return 0;
                }

                success = deleteMessageFromIcc(subId, messageIndex);
                // Notify changes even failure case since there might be some changes should be
                // known.
                getContext()
                        .getContentResolver()
                        .notifyChange(
                                match == SMS_ICC ? ICC_URI : ICC_SUBID_URI,
                                null,
                                true,
                                UserHandle.USER_ALL);
                return success ? 1 : 0; // return deleted count
            }

            default:
                throw new IllegalArgumentException("Unknown URL");
        }

        if (count > 0) {
            notifyChange(notifyIfNotDefault, url, getCallingPackage());
        }
        return count;
    }

    /**
     * Deletes the message at index from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param messageIndex the message index of the message in the ICC (1-based index).
     * @return true for succeess. Otherwise false.
     */
    private boolean deleteMessageFromIcc(int subId, int messageIndex) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            return smsManager.deleteMessageFromIcc(messageIndex);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Deletes all the messages from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @return return deleted messaegs count.
     */
    private int deleteAllMessagesFromIcc(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            int deletedCnt = 0;
            int maxIndex = smsManager.getSmsCapacityOnIcc();
            // messageIndex is 1-based index of the message in the ICC.
            for (int messageIndex = 1; messageIndex <= maxIndex; messageIndex++) {
                if (smsManager.deleteMessageFromIcc(messageIndex)) {
                    deletedCnt++;
                } else {
                    Log.e(TAG, "Fail to delete SMS at index " + messageIndex
                            + " for subId " + subId);
                }
            }
            return deletedCnt;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        final int callerUid = 1001;
        final UserHandle callerUserHandle = UserHandle.getUserHandleForUid(1001);
        final String callerPkg = getCallingPackage();
        Log.e(TAG, "update: " + callerPkg);
        int count = 0;
        String table = TABLE_SMS;
        String extraWhere = null;
        boolean notifyIfNotDefault = true;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = getWritableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callerPkg + ";SmsProvider.update;" + url, false);
        }

        switch (match) {
            case SMS_RAW_MESSAGE:
                table = TABLE_RAW;
                notifyIfNotDefault = false;
                break;

            case SMS_STATUS_PENDING:
                table = TABLE_SR_PENDING;
                break;

            case SMS_ALL:
            case SMS_FAILED:
            case SMS_QUEUED:
            case SMS_INBOX:
            case SMS_SENT:
            case SMS_DRAFT:
            case SMS_OUTBOX:
            case SMS_CONVERSATIONS:
                break;

            case SMS_ALL_ID:
                extraWhere = "_id=" + url.getPathSegments().get(0);
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            case SMS_CONVERSATIONS_ID: {
                String threadId = url.getPathSegments().get(1);

                try {
                    Integer.parseInt(threadId);
                } catch (Exception ex) {
                    Log.e(TAG, "Bad conversation thread id: " + threadId);
                    break;
                }

                extraWhere = "thread_id=" + threadId;
                break;
            }

            case SMS_STATUS_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            default:
                throw new UnsupportedOperationException(
                        "URI " + url + " not supported");
        }

        if (table.equals(TABLE_SMS) && ProviderUtil.shouldRemoveCreator(values, callerUid)) {
            // CREATOR should not be changed by non-SYSTEM/PHONE apps
            Log.w(TAG, callerPkg + " tries to update CREATOR");
            values.remove(Sms.CREATOR);
        }

        final long token = Binder.clearCallingIdentity();
        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle);
            if (table.equals(TABLE_SMS)) {
                selectionByEmergencyNumbers = ProviderUtil
                        .getSelectionByEmergencyNumbers(getContext());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (table.equals(TABLE_SMS)) {
            if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
                // No subscriptions associated with user and no emergency numbers, return 0.
                return 0;
            }
        } else {
            if (selectionBySubIds == null) {
                // No subscriptions associated with user, return 0.
                return 0;
            }
        }


        String filter = "";
        if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }
        where = DatabaseUtils.concatenateWhere(where, filter);

        where = DatabaseUtils.concatenateWhere(where, extraWhere);
        count = db.update(table, values, where, whereArgs);

        if (count > 0) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "update " + url + " succeeded");
            }
            notifyChange(notifyIfNotDefault, url, callerPkg);
        }
        return count;
    }

    private void notifyChange(boolean notifyIfNotDefault, Uri uri, final String callingPackage) {
        final Context context = getContext();
        ContentResolver cr = context.getContentResolver();
        cr.notifyChange(uri, null, true, UserHandle.USER_ALL);
        cr.notifyChange(MmsSms.CONTENT_URI, null, true, UserHandle.USER_ALL);
        cr.notifyChange(Uri.parse("content://mms-sms/conversations/"), null, true,
                UserHandle.USER_ALL);
        if (notifyIfNotDefault) {
            ProviderUtil.notifyIfNotDefaultSmsApp(uri, callingPackage, context);
        }
    }

    // Db open helper for tables stored in CE(Credential Encrypted) storage.
    @VisibleForTesting
    public SQLiteOpenHelper mCeOpenHelper;
    // Db open helper for tables stored in DE(Device Encrypted) storage. It's currently only used
    // to store raw table.
    @VisibleForTesting
    public SQLiteOpenHelper mDeOpenHelper;

    private final static String TAG = "SmsProvider";
    private final static String VND_ANDROID_SMS = "vnd.android.cursor.item/sms";
    private final static String VND_ANDROID_SMSCHAT =
            "vnd.android.cursor.item/sms-chat";
    private final static String VND_ANDROID_DIR_SMS =
            "vnd.android.cursor.dir/sms";

    private static final String[] sIDProjection = new String[] { "_id" };

    private static final int SMS_ALL = 0;
    private static final int SMS_ALL_ID = 1;
    private static final int SMS_INBOX = 2;
    private static final int SMS_INBOX_ID = 3;
    private static final int SMS_SENT = 4;
    private static final int SMS_SENT_ID = 5;
    private static final int SMS_DRAFT = 6;
    private static final int SMS_DRAFT_ID = 7;
    private static final int SMS_OUTBOX = 8;
    private static final int SMS_OUTBOX_ID = 9;
    private static final int SMS_CONVERSATIONS = 10;
    private static final int SMS_CONVERSATIONS_ID = 11;
    private static final int SMS_RAW_MESSAGE = 15;
    private static final int SMS_ATTACHMENT = 16;
    private static final int SMS_ATTACHMENT_ID = 17;
    private static final int SMS_NEW_THREAD_ID = 18;
    private static final int SMS_QUERY_THREAD_ID = 19;
    private static final int SMS_STATUS_ID = 20;
    private static final int SMS_STATUS_PENDING = 21;
    private static final int SMS_ALL_ICC = 22;
    private static final int SMS_ICC = 23;
    private static final int SMS_FAILED = 24;
    private static final int SMS_FAILED_ID = 25;
    private static final int SMS_QUEUED = 26;
    private static final int SMS_UNDELIVERED = 27;
    private static final int SMS_RAW_MESSAGE_PERMANENT_DELETE = 28;
    private static final int SMS_ALL_ICC_SUBID = 29;
    private static final int SMS_ICC_SUBID = 30;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("sms", null, SMS_ALL);
        sURLMatcher.addURI("sms", "#", SMS_ALL_ID);
        sURLMatcher.addURI("sms", "inbox", SMS_INBOX);
        sURLMatcher.addURI("sms", "inbox/#", SMS_INBOX_ID);
        sURLMatcher.addURI("sms", "sent", SMS_SENT);
        sURLMatcher.addURI("sms", "sent/#", SMS_SENT_ID);
        sURLMatcher.addURI("sms", "draft", SMS_DRAFT);
        sURLMatcher.addURI("sms", "draft/#", SMS_DRAFT_ID);
        sURLMatcher.addURI("sms", "outbox", SMS_OUTBOX);
        sURLMatcher.addURI("sms", "outbox/#", SMS_OUTBOX_ID);
        sURLMatcher.addURI("sms", "undelivered", SMS_UNDELIVERED);
        sURLMatcher.addURI("sms", "failed", SMS_FAILED);
        sURLMatcher.addURI("sms", "failed/#", SMS_FAILED_ID);
        sURLMatcher.addURI("sms", "queued", SMS_QUEUED);
        sURLMatcher.addURI("sms", "conversations", SMS_CONVERSATIONS);
        sURLMatcher.addURI("sms", "conversations/#", SMS_CONVERSATIONS_ID);
        sURLMatcher.addURI("sms", "raw", SMS_RAW_MESSAGE);
        sURLMatcher.addURI("sms", "raw/permanentDelete", SMS_RAW_MESSAGE_PERMANENT_DELETE);
        sURLMatcher.addURI("sms", "attachments", SMS_ATTACHMENT);
        sURLMatcher.addURI("sms", "attachments/#", SMS_ATTACHMENT_ID);
        sURLMatcher.addURI("sms", "threadID", SMS_NEW_THREAD_ID);
        sURLMatcher.addURI("sms", "threadID/#", SMS_QUERY_THREAD_ID);
        sURLMatcher.addURI("sms", "status/#", SMS_STATUS_ID);
        sURLMatcher.addURI("sms", "sr_pending", SMS_STATUS_PENDING);
        sURLMatcher.addURI("sms", "icc", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "icc/#", SMS_ICC);
        sURLMatcher.addURI("sms", "icc_subId/#", SMS_ALL_ICC_SUBID);
        sURLMatcher.addURI("sms", "icc_subId/#/#", SMS_ICC_SUBID);
        //we keep these for not breaking old applications
        sURLMatcher.addURI("sms", "sim", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "sim/#", SMS_ICC);
    }

    /**
     * These methods can be overridden in a subclass for testing SmsProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase(int match) {
        return getDBOpenHelper(match).getReadableDatabase();
    }

    SQLiteDatabase getWritableDatabase(int match) {
        return  getDBOpenHelper(match).getWritableDatabase();
    }

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    UserHandle userToBeRemoved  = intent.getParcelableExtra(Intent.EXTRA_USER,
                            UserHandle.class);
                    UserManager userManager = context.getSystemService(UserManager.class);
                    if ((userToBeRemoved == null) || (userManager == null) ||
                            (!userManager.isManagedProfile(userToBeRemoved.getIdentifier()))) {
                        // Do not delete SMS if removed profile is not managed profile.
                        return;
                    }
                    Log.d(TAG, "Received ACTION_USER_REMOVED for managed profile - Deleting SMS.");

                    // Deleting SMS related to managed profile.
                    Uri uri = Sms.CONTENT_URI;
                    int match = sURLMatcher.match(uri);
                    SQLiteDatabase db = getWritableDatabase(match);

                    final long token = Binder.clearCallingIdentity();
                    String selectionBySubIds;
                    try {
                        // Filter SMS based on subId.
                        selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                                userToBeRemoved);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    if (selectionBySubIds == null) {
                        // No subscriptions associated with user, return.
                        return;
                    }

                    int count = db.delete(TABLE_SMS, selectionBySubIds, null);
                    if (count != 0) {
                        // Don't update threads unless something changed.
                        MmsSmsDatabaseHelper.updateThreads(db, selectionBySubIds, null);
                        notifyChange(true, uri, getCallingPackage());
                    }
                    break;
            }
        }
    };
}
