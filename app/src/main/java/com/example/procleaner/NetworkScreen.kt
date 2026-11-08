package com.example.procleaner

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun NetworkScreen(resumes: Int) {
    val ctx = LocalContext.current
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val network = remember(resumes) { cm.activeNetwork }
    val caps = remember(resumes) { network?.let { cm.getNetworkCapabilities(it) } }
    val links = remember(resumes) { network?.let { cm.getLinkProperties(it) } }
    val wifiRssi = remember(resumes, caps) {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
        else {
            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                caps.transportInfo as? WifiInfo
            } else {
                ctx.getSystemService(WifiManager::class.java).connectionInfo
            }
            wifiInfo?.rssi?.takeIf { it in -126..-1 }
        }
    }
    val cellSignal = remember(resumes, caps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true
        ) null else runCatching {
            ctx.getSystemService(TelephonyManager::class.java).signalStrength
        }.getOrNull()
    }
    val cellDbm = remember(cellSignal) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else cellSignal?.cellSignalStrengths?.maxByOrNull { it.level }?.dbm
            ?.takeIf { it in -200..-1 }
    }
    val type = when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
        else -> "Connected"
    }
    val addresses = links?.linkAddresses
        ?.mapNotNull { it.address.hostAddress }
        ?.filter { !it.startsWith("127.") && it != "::1" }
        ?.distinct()
        ?.joinToString("\n")
        .orEmpty()
    val dns = links?.dnsServers
        ?.mapNotNull { it.hostAddress }
        ?.distinct()
        ?.joinToString("\n")
        .orEmpty()
    val gateway = links?.routes
        ?.firstOrNull { it.isDefaultRoute }
        ?.gateway?.hostAddress
        ?: "Unavailable"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Network", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Type", type)
                    InfoRow("Internet", when {
                        caps == null -> "Not connected"
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "Available"
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "Connected, not verified"
                        else -> "No internet"
                    })
                    InfoRow("Metered", if (cm.isActiveNetworkMetered) "Yes" else "No")
                }
            }
        }
        item {
            Card {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Signal strength", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Wi-Fi", wifiRssi?.let { "$it dBm · ${wifiQuality(it)}" } ?: "Not connected")
                    InfoRow(
                        "Cellular",
                        cellSignal?.let {
                            val bars = "${it.level}/4 bars"
                            if (cellDbm != null) "$cellDbm dBm · $bars" else bars
                        } ?: if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                            "Unavailable"
                        } else "Not connected"
                    )
                    Text(
                        "Cellular strength is the latest reading available from Android and may be delayed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Network addresses", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Local IP", addresses.ifBlank { "Unavailable" })
                    InfoRow("Gateway", gateway)
                    InfoRow("DNS servers", dns.ifBlank { "Unavailable" })
                }
            }
        }
        item {
            Text(
                "This screen shows connection details reported by Android. Wi-Fi names and passwords are not accessed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun wifiQuality(rssi: Int): String = when {
    rssi >= -50 -> "Excellent"
    rssi >= -60 -> "Good"
    rssi >= -70 -> "Fair"
    else -> "Weak"
}
