package com.pttconnect.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pttconnect.app.R
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.MySubscription
import com.pttconnect.app.data.Plan
import com.pttconnect.app.data.formatRand
import com.pttconnect.app.data.friendlyError
import kotlinx.coroutines.launch

private val Orange = Color(0xFFF47B20)
private val Navy = Color(0xFF0F2A4F)

/**
 * Plans + monthly/yearly toggle. Prices come live from the database (admin can change them).
 * "Choose" opens PayFast in the browser; when the user comes back, the status refreshes.
 */
@Composable
fun SubscriptionScreen(backend: Backend, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<Plan>?>(null) }
    var mine by remember { mutableStateOf<MySubscription?>(null) }
    var yearly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }

    fun load() = scope.launch {
        try { plans = backend.plans(); mine = backend.mySubscription(); msg = null } catch (e: Exception) { msg = friendlyError(e) }
    }

    // Refresh every time the screen becomes visible again (e.g. returning from PayFast).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) load() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Pal.text) }
            Image(painterResource(R.drawable.brand_logo), null, Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Text("Subscription", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }

        mine?.let { StatusCard(it) }
        msg?.let { Text(it, color = Pal.amber) }

        // Monthly / Yearly toggle
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pal.panel).padding(4.dp)) {
            listOf(false to "Monthly", true to "Yearly").forEach { (isYear, label) ->
                val sel = yearly == isYear
                Text(label, color = if (sel) Color.White else Pal.muted, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (sel) Orange else Color.Transparent)
                        .clickable { yearly = isYear }.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        val list = plans
        if (list == null && msg == null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Loading plans…", color = Pal.muted)
        }
        list?.forEach { plan ->
            PlanCard(plan, yearly, current = mine?.planCode == plan.code && mine?.source == "subscription", busy = busy == plan.code,
                onChoose = {
                    busy = plan.code; msg = null
                    scope.launch {
                        try {
                            val url = backend.startCheckout(plan.code, yearly)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) { msg = friendlyError(e) }
                        busy = null
                    }
                },
                onContact = {
                    val to = mine?.salesEmail ?: ""
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).putExtra(Intent.EXTRA_SUBJECT, "PTT Connect – ${plan.name} plan enquiry")
                    runCatching { ctx.startActivity(i) }.onFailure { msg = "E-mail us at $to" }
                })
        }

        val m = mine
        if (m != null && m.source == "subscription" && m.provider == "payfast" && m.status == "active" && !m.cancelAtPeriodEnd) {
            TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel my subscription", color = Pal.red) }
        }
        Text("Payments are processed securely by PayFast. Prices in South African Rand (ZAR). " +
            "Subscriptions renew automatically until cancelled.", color = Pal.muted, fontSize = 12.sp)
    }

    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Cancel subscription?") },
        text = { Text("You keep access until ${mine?.endsAt ?: "the end of the paid period"}. You won't be charged again.") },
        confirmButton = { TextButton(onClick = {
            confirmCancel = false
            scope.launch { try { backend.cancelSubscription(); load() } catch (e: Exception) { msg = friendlyError(e) } }
        }) { Text("Yes, cancel", color = Pal.red) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
    )
}

@Composable
private fun StatusCard(m: MySubscription) {
    val (title, color) = when {
        m.source == "admin" -> "Administrator – full access" to Pal.green
        m.source == "trial" -> "Free trial (${m.planName ?: "Basic"}) until ${m.endsAt}" to Pal.green
        m.source == "subscription" && m.cancelAtPeriodEnd -> "${m.planName} – ends ${m.endsAt}, not renewing" to Pal.amber
        m.source == "subscription" -> "${m.planName} (${m.period}) – renews ${m.endsAt}" to Pal.green
        !m.hasAccess -> "No active plan – choose one below" to Pal.red
        else -> "No plan yet (test mode: full access)" to Pal.muted
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.panel).border(1.dp, color, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = color); Spacer(Modifier.width(8.dp)); Text(title, color = Pal.text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlanCard(plan: Plan, yearly: Boolean, current: Boolean, busy: Boolean, onChoose: () -> Unit, onContact: () -> Unit) {
    val price = plan.priceCents(yearly)
    val highlight = plan.code == "pro"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.panel)
            .border(if (highlight || current) 2.dp else 1.dp, if (current) Pal.green else if (highlight) Orange else Pal.line, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plan.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.text, modifier = Modifier.weight(1f))
            if (current) Text("YOUR PLAN", color = Pal.green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else if (highlight) Text("POPULAR", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        plan.description?.let { Text(it, color = Pal.muted, fontSize = 14.sp) }
        if (price != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatRand(price), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Pal.text)
                Text(if (yearly) " / year" else " / month", color = Pal.muted, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (yearly) plan.yearlySavingPct?.let { Text("Save $it% vs monthly", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        } else {
            Text(if (plan.isCustom) "Custom price" else "Not available ${if (yearly) "yearly" else "monthly"}",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }
        plan.features.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Pal.green, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(f, color = Pal.text, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            current -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Current plan") }
            price != null -> Button(onClick = onChoose, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (highlight) Orange else Navy, contentColor = Color.White)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Choose ${plan.name} – ${formatRand(price)}", fontWeight = FontWeight.SemiBold)
            }
            plan.isCustom -> OutlinedButton(onClick = onContact, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Contact us") }
        }
    }
}
