package tw.nekomimi.nekogram.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 公共 Documents 目录里的 Telegram 缓存残留清理。
 *
 * <p>背景：应用没有申请 MANAGE_EXTERNAL_STORAGE，在分区存储下既写不进公共 Documents，
 * 也无法用 listFiles() 看到别的应用写进去的条目。设备上出现的 dcId_docId 形态文件
 * （m3u8 清单、无扩展名的分片与初始化段）来自持有全盘权限的第三方程序，
 * 普通文件 API 完全触及不到。
 *
 * <p>唯一权限最小且合规的通道是 SAF：用户在系统选择器里手动授予 Documents 目录，
 * 之后应用可以枚举并通过 DocumentsProvider 删除该目录下的任意条目。
 * 授权由系统持久化，重启后仍然有效。
 */
public final class DocumentsLeftoverCleaner {

    /** 目录授权请求码，与设置页 onActivityResultFragment 对应。 */
    public static final int REQUEST_CODE = 0x0D0C;

    private static final String DOCUMENTS_DOC_ID = "primary:Documents";
    private static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";
    private static final String DOCUMENTS_INITIAL_URI =
            "content://com.android.externalstorage.documents/document/primary%3ADocuments";

    /**
     * 缓存形态：dcId_docId，可选带 1~6 位字母数字扩展名。
     * dcId 是 1~4 位正整数，docId 是至少 5 位的长整数。
     * 正常保存的文件带 photo_ / video_ 前缀或可读文件名，不会命中。
     */
    private static final Pattern LEFTOVER_NAME =
            Pattern.compile("^[1-9][0-9]{0,3}_[0-9]{5,}(\\.[A-Za-z0-9]{1,6})?$");

    private DocumentsLeftoverCleaner() {
    }

    /** 一条目录项。 */
    public static final class Entry {
        public final String documentId;
        public final String name;
        public final long size;
        public final long modified;
        public final boolean leftover;
        public final boolean hls;

        Entry(String documentId, String name, long size, long modified, boolean leftover, boolean hls) {
            this.documentId = documentId;
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.leftover = leftover;
            this.hls = hls;
        }
    }

    /** 当前是否已经持有 Documents 目录的持久化写权限。 */
    public static boolean hasAccess(Context context) {
        return grantedTreeUri(context) != null;
    }

    /** 取出已授权的 Documents 树 Uri，没有则返回 null。 */
    public static Uri grantedTreeUri(Context context) {
        if (context == null) {
            return null;
        }
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (!permission.isWritePermission()) {
                    continue;
                }
                Uri uri = permission.getUri();
                if (isDocumentsTree(uri)) {
                    return uri;
                }
            }
        } catch (Throwable t) {
            FileLog.e("DocumentsLeftoverCleaner: read persisted permissions failed", t);
        }
        return null;
    }

    private static boolean isDocumentsTree(Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            return DOCUMENTS_DOC_ID.equalsIgnoreCase(DocumentsContract.getTreeDocumentId(uri));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 构造系统目录选择器，默认停在内部存储的 Documents。 */
    public static Intent buildRequestIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra(EXTRA_INITIAL_URI, DOCUMENTS_INITIAL_URI);
        return intent;
    }

    /** 系统选择器返回后保存授权。 */
    public static boolean takeAccess(Context context, Intent data) {
        if (context == null || data == null) {
            return false;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return false;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            FileLog.e("DocumentsLeftoverCleaner: takePersistableUriPermission failed", t);
            return false;
        }
        if (hasAccess(context)) {
            return true;
        }
        // 用户选了别的目录，把刚拿到的授权退掉，避免留下无用权限
        try {
            context.getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 列出 Documents 根目录下的全部文件项（不含子目录）。 */
    public static List<Entry> scan(Context context) {
        List<Entry> entries = new ArrayList<>();
        Uri tree = grantedTreeUri(context);
        if (tree == null) {
            return entries;
        }
        ContentResolver resolver = context.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        Cursor cursor = null;
        try {
            cursor = resolver.query(children, projection, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    continue;
                }
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                boolean leftover = name != null && LEFTOVER_NAME.matcher(name).matches();
                boolean hls = leftover && looksLikeHls(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId), size);
                entries.add(new Entry(documentId, name, size, modified, leftover, hls));
            }
        } catch (Throwable t) {
            FileLog.e("DocumentsLeftoverCleaner: scan failed", t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return entries;
    }

    /** 读前 7 个字节判断是不是 HLS 清单，只在文件不大时读。 */
    private static boolean looksLikeHls(ContentResolver resolver, Uri uri, long size) {
        if (size < 0 || size > 64 * 1024) {
            return false;
        }
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                return false;
            }
            byte[] head = new byte[7];
            int read = 0;
            while (read < head.length) {
                int step = in.read(head, read, head.length - read);
                if (step <= 0) {
                    break;
                }
                read += step;
            }
            return read == head.length
                    && head[0] == '#' && head[1] == 'E' && head[2] == 'X'
                    && head[3] == 'T' && head[4] == 'M' && head[5] == '3' && head[6] == 'U';
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 删除全部缓存形态的条目，返回成功数量。 */
    public static int deleteLeftovers(Context context, List<Entry> entries) {
        Uri tree = grantedTreeUri(context);
        if (tree == null || entries == null) {
            return 0;
        }
        ContentResolver resolver = context.getContentResolver();
        int deleted = 0;
        for (Entry entry : entries) {
            if (!entry.leftover) {
                continue;
            }
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId);
            try {
                if (DocumentsContract.deleteDocument(resolver, document)) {
                    deleted++;
                }
            } catch (Throwable t) {
                FileLog.e("DocumentsLeftoverCleaner: delete failed " + entry.name, t);
            }
        }
        return deleted;
    }
}
