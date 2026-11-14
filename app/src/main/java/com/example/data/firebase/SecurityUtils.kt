package com.example.data.firebase

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SecurityUtils {
    fun sha256(input: String): String {
        if (input.isEmpty()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun maskEmail(email: String?): String {
        val e = email?.trim().orEmpty()
        val at = e.indexOf('@')
        if (at <= 1) return e
        val prefix = e.substring(0, 2)
        val domain = e.substring(at)
        return "$prefix***$domain"
    }
}

object ValidationUtils {
    private val ID_REGEX = Regex("^[A-Z0-9_-]{4,30}$")
    private val PHONE_REGEX = Regex("^[+]?[0-9\\s-]{10,15}$")

    fun normalizeCode(raw: String): String =
        raw.trim().uppercase(Locale.US).replace(Regex("\\s+"), "-")

    fun validateShopOrAdminId(raw: String): String? {
        val code = normalizeCode(raw)
        return when {
            code.isBlank() -> "ID is required / ID डालना ज़रूरी है"
            !ID_REGEX.matches(code) -> "ID must be 4-30 characters (letters, numbers, -, _ only) / ID 4-30 अक्षरों (A-Z, 0-9, -, _) की होनी चाहिए"
            else -> null
        }
    }

    fun validateShopName(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.length < 2 -> "Shop Name must be at least 2 characters / दुकान का नाम कम से कम 2 अक्षर का हो"
            trimmed.length > 100 -> "Shop Name too long (max 100 chars)"
            else -> null
        }
    }

    fun validatePersonName(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.length < 2 -> "Owner / Admin Name is required / नाम डालना ज़रूरी है"
            trimmed.length > 100 -> "Name too long"
            else -> null
        }
    }

    fun validateMobile(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            !PHONE_REGEX.matches(trimmed) || digits.length < 10 ->
                "Enter a valid 10-digit mobile number / कृपया सही 10 अंकों का मोबाइल नंबर डालें"
            else -> null
        }
    }

    fun validatePassword(raw: String, confirm: String? = null): String? {
        return when {
            raw.length < 6 -> "Password must be at least 6 characters / पासवर्ड कम से कम 6 अक्षरों का होना चाहिए"
            confirm != null && raw != confirm -> "Passwords do not match / दोनों पासवर्ड मेल नहीं खा रहे हैं"
            else -> null
        }
    }

    fun validateSecretCode(raw: String): String? {
        return if (raw.trim().length < 4) {
            "Secret Code must be at least 4 characters / सीक्रेट कोड कम से कम 4 अक्षर का हो"
        } else null
    }
}

object NetworkUtils {
    fun isOnline(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun isNetworkOrServerUnavailable(error: Throwable?): Boolean {
        if (error == null) return false
        return generateSequence(error) { it.cause }.any { cause ->
            (cause is FirebaseFirestoreException && (
                cause.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                    cause.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                )) ||
                cause is FirebaseNetworkException ||
                cause is kotlinx.coroutines.TimeoutCancellationException ||
                cause is UnknownHostException ||
                cause is IOException ||
                (cause.message?.contains("offline", ignoreCase = true) == true) ||
                (cause.message?.contains("UNAVAILABLE", ignoreCase = true) == true) ||
                (cause.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                (cause.message?.contains("Timed out", ignoreCase = true) == true)
        }
    }

    fun mapCloudError(error: Throwable?): String {
        if (error == null) return "Unknown error"
        if (isNetworkOrServerUnavailable(error)) {
            return "Firebase / Internet connection is unavailable. Please check your network and try again.\nFirebase या इंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें और पुनः प्रयास करें।"
        }
        return error.localizedMessage ?: "Operation failed"
    }
}

object Formatters {
    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    fun currency(amount: Double): String = inrFormat.format(amount)

    fun date(millis: Long): String {
        if (millis <= 0L) return "-"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
    }

    fun dateTime(millis: Long): String {
        if (millis <= 0L) return "-"
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    fun dateKey(millis: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }

    fun monthKey(millis: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date(millis))
    }
}
