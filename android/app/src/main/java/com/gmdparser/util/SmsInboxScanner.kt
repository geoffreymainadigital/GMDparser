package com.gmdparser.util

import android.content.Context
import android.provider.Telephony
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.parser.MpesaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanStats(
    val totalSmsScanned: Int,
    val mpesaFound: Int,
    val newlyQueued: Int,
    val alreadyTracked: Int
)

object SmsInboxScanner {

    suspend fun scanInbox(context: Context, daysBack: Int? = 30): ScanStats = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.Inbox._ID,
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE
        )

        val (selection, selectionArgs) = if (daysBack != null && daysBack > 0) {
            val cutoffMillis = System.currentTimeMillis() - (daysBack.toLong() * 24L * 60L * 60L * 1000L)
            Pair("${Telephony.Sms.Inbox.DATE} >= ?", arrayOf(cutoffMillis.toString()))
        } else {
            Pair(null, null)
        }

        val sortOrder = "${Telephony.Sms.Inbox.DATE} DESC"

        var totalScanned = 0
        val mpesaTransactions = mutableListOf<Transaction>()

        try {
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)

                while (cursor.moveToNext()) {
                    totalScanned++
                    val body = cursor.getString(bodyIdx) ?: continue
                    val address = if (addressIdx >= 0) cursor.getString(addressIdx) else null

                    // Quick filter: Check address or keywords before full regex
                    val isLikelyMpesa = (address != null && address.contains("MPESA", ignoreCase = true)) ||
                        body.contains("Confirmed", ignoreCase = true) ||
                        body.contains("M-PESA", ignoreCase = true)

                    if (isLikelyMpesa) {
                        val parsed = MpesaParser.parse(body)
                        if (parsed != null && parsed.transactionCode.isNotBlank()) {
                            mpesaTransactions.add(parsed)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Add to TransactionRepository which deduplicates against existing pending and confirmed
        val newlyQueued = TransactionRepository.addPendingTransactions(mpesaTransactions)
        val alreadyTracked = mpesaTransactions.size - newlyQueued

        ScanStats(
            totalSmsScanned = totalScanned,
            mpesaFound = mpesaTransactions.size,
            newlyQueued = newlyQueued,
            alreadyTracked = alreadyTracked
        )
    }
}
