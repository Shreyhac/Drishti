package com.drishti.app.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BarcodeEngine {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    suspend fun scan(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { barcodes ->
                val results = barcodes.mapNotNull { bc ->
                    when (bc.valueType) {
                        Barcode.TYPE_URL           -> "URL: ${bc.url?.url}"
                        Barcode.TYPE_PRODUCT       -> "Product code: ${bc.rawValue}"
                        Barcode.TYPE_WIFI          -> "WiFi network: ${bc.wifi?.ssid}, password: ${bc.wifi?.password}"
                        Barcode.TYPE_CONTACT_INFO  -> "Contact: ${bc.contactInfo?.name?.formattedName}"
                        Barcode.TYPE_PHONE         -> "Phone number: ${bc.phone?.number}"
                        Barcode.TYPE_EMAIL         -> "Email: ${bc.email?.address}"
                        Barcode.TYPE_SMS           -> "SMS to: ${bc.sms?.phoneNumber}"
                        Barcode.TYPE_GEO           -> "Location: ${bc.geoPoint?.lat}, ${bc.geoPoint?.lng}"
                        Barcode.TYPE_CALENDAR_EVENT -> "Event: ${bc.calendarEvent?.summary}"
                        Barcode.TYPE_DRIVER_LICENSE -> "ID document"
                        Barcode.TYPE_TEXT          -> bc.rawValue
                        else                       -> bc.rawValue?.takeIf { it.isNotBlank() }
                    }
                }
                cont.resume(results.joinToString("; "))
            }
            .addOnFailureListener { cont.resume("") }
    }
}
