package com.hippo.unifile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Lets UniFile treat trees of the debug no-rename provider as SAF trees. UniFile only recognizes authorities that
 * PackageManager lists as document providers, and the platform leaves the app's own provider out of that list
 * under instrumentation. Every operation on the returned file still goes through the real provider.
 */
class TestProviderTreeHandler(private val authority: String) : UriHandler {
    override fun fromUri(context: Context, uri: Uri): UniFile? {
        if (uri.authority != authority || !DocumentsContract.isTreeUri(uri)) return null
        val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
        return TreeDocumentFile(null, context, DocumentsContract.buildDocumentUriUsingTree(uri, documentId))
    }
}
