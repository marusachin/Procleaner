package com.example.procleaner

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { CleanState() }
    var tab by remember { mutableIntStateOf(0) }
    var resumes by remember { mutableIntStateOf(0) }
    var boostTab by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val scan: () -> Unit = {
        if (!hasAccess(ctx)) openAccessSettings(ctx)
        else if (!state.scanning) scope.launch { state.scan(ctx) }
    }
    val tabs = listOf("Home" to Icons.Default.Home, "Clean" to Icons.Default.Delete, "Boost" to Icons.Default.Settings,
        "Apps" to Icons.Default.Menu, "Device" to Icons.Default.Info, "Network" to Icons.Default.Wifi)
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (t, ic) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(ic, t) }, label = { Text(t) })
            }
        }
    }) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(state, resumes, scan, onOpenClean = { tab = 1 }, onBoost = { i -> boostTab = i; tab = 2 })
                1 -> CleanScreen(state, scan)
                2 -> BoostScreen(boostTab, { boostTab = it }, resumes)
                3 -> AppsScreen(resumes)
                4 -> DeviceScreen(resumes)
                else -> NetworkScreen(resumes)
            }
        }
    }
}
