package com.glooboo.umessage.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.extensions.dialNumber
import com.glooboo.umessage.helpers.EXTRA_VCARD_URI
import com.glooboo.umessage.helpers.parseVCardFromUri
import com.glooboo.umessage.models.VCardPropertyWrapper
import com.glooboo.umessage.models.VCardWrapper
import com.glooboo.umessage.ui.vcard.VCardViewerScreen
import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.Telephone
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.extensions.sendEmailIntent
import org.fossify.commons.extensions.toast

/** Shows the contacts of a vCard attachment; the intent extra [EXTRA_VCARD_URI] is unchanged. */
class VCardViewerActivity : ComposeSimpleActivity() {

    /** Null while the file is being parsed. */
    private var contacts by mutableStateOf<List<VCardWrapper>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vCardUri = intent.getParcelableExtra(EXTRA_VCARD_URI) as? Uri
        setUMessageContent {
            VCardViewerScreen(
                contacts = contacts,
                onBack = ::finish,
                onAddContact = vCardUri?.let { uri -> { addContact(uri) } },
                onProperty = ::handleClick,
                isActionable = { it.property is Telephone || it.property is Email },
            )
        }

        if (vCardUri != null) {
            parseVCardFromUri(this, vCardUri) {
                runOnUiThread { contacts = prepareData(it) }
            }
        } else {
            contacts = emptyList()
        }
    }

    private fun addContact(vCardUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val mimetype = contentResolver.getType(vCardUri)
            setDataAndType(vCardUri, mimetype?.lowercase())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.system_service_disabled, android.widget.Toast.LENGTH_LONG)
        }
    }

    private fun handleClick(property: VCardPropertyWrapper) {
        when (property.property) {
            is Telephone -> dialNumber(property.value.normalizePhoneNumber())
            is Email -> sendEmailIntent(property.value)
        }
    }

    private fun prepareData(vCards: List<VCard>): List<VCardWrapper> {
        return vCards.map { vCard -> VCardWrapper.from(this, vCard) }
    }
}
