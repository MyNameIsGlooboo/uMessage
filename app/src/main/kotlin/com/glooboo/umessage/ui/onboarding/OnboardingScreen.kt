package com.glooboo.umessage.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import com.glooboo.umessage.ui.theme.UMessageTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.OemStep
import com.glooboo.umessage.helpers.OemStepKind
import com.glooboo.umessage.helpers.StepCheck
import com.glooboo.umessage.helpers.StepState
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.settings.AppLanguage
import com.glooboo.umessage.ui.theme.success

private fun Context.isGranted(permissions: List<String>) = permissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    reduceMotion: Boolean,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped on resume and after every system dialog so status rows re-read live state.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val items = remember { permissionItems() }
    // Reading `refresh` makes these recompute when the user comes back from a system screen.
    val isDefault = refresh.let { OnboardingActivity.isDefaultSmsApp(context) }
    val grantedMap = refresh.let { items.associateWith { context.isGranted(it.permissions) } }
    val allGranted = grantedMap.values.all { it }
    // Fixed when the flow opens, so the Allow step does not vanish while the user is on it.
    val pages = remember { OnboardingState.pagesFor(allowNeeded = !(isDefault && allGranted)) }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    var defaultDenied by rememberSaveable { mutableStateOf(false) }
    var defaultUnavailable by rememberSaveable { mutableStateOf(false) }
    // Set by "Allow all" while the default-app dialog is open: when it closes, the permissions are asked next.
    var allowAllPending by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(emptySet<String>()) }

    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val purgeDays by viewModel.purgeDays.collectAsStateWithLifecycle()
    val deliveryReports by viewModel.deliveryReports.collectAsStateWithLifecycle()

    // Raised after the default-app dialog closes during "Allow all"; handled once the screen is resumed again.
    var continueAllowAll by remember { mutableStateOf(false) }
    val defaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refresh++
        defaultDenied = !OnboardingActivity.isDefaultSmsApp(context)
        if (allowAllPending) {
            allowAllPending = false
            continueAllowAll = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val activity = context.findActivity()
        val blocked = result.filter { (perm, ok) ->
            !ok && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }.keys
        permanentlyDenied = permanentlyDenied + blocked
        refresh++
    }

    fun goTo(page: Int) {
        scope.launch {
            if (reduceMotion) pagerState.scrollToPage(page) else pagerState.animateScrollToPage(page)
        }
    }

    /** Opens the default-app dialog. Returns false when the phone could not show it. */
    fun requestDefault(): Boolean {
        defaultDenied = false
        val intent = OnboardingActivity.defaultSmsRequestIntent(context)
        val launched = intent != null && runCatching { defaultLauncher.launch(intent) }.isSuccess
        if (!launched) defaultUnavailable = true
        return launched
    }

    fun requestPermissions(list: List<String>) {
        val missing = list.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        if (missing.all { it in permanentlyDenied }) {
            context.openAppSettings()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun requestAllMissing() =
        requestPermissions(items.filter { !context.isGranted(it.permissions) }.flatMap { it.permissions })

    // "Allow all": the default-app role first when it is missing, then ONE combined permission request.
    fun allowAll() {
        if (OnboardingActivity.isDefaultSmsApp(context)) {
            requestAllMissing()
        } else {
            allowAllPending = true
            if (!requestDefault()) {
                allowAllPending = false
                requestAllMissing()
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(continueAllowAll) {
        if (continueAllowAll) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            continueAllowAll = false
            requestAllMissing()
        }
    }

    val page = pagerState.currentPage
    val current = pages[page]
    BackHandler(enabled = page > 0) { goTo(page - 1) }

    val nextLabel = when (current) {
        OnboardingPage.WELCOME -> stringResource(R.string.onb_welcome_start)
        OnboardingPage.ALLOW ->
            if (isDefault && allGranted) stringResource(R.string.onb_next) else stringResource(R.string.onb_skip)
        OnboardingPage.KEEP_RUNNING -> stringResource(R.string.onb_finish)
        OnboardingPage.MODE -> stringResource(R.string.onb_next)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 0,
            ) { index ->
                // Only the settled page is exposed to accessibility services.
                val pageModifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                when (pages[index]) {
                    OnboardingPage.WELCOME -> WelcomePage(
                        modifier = pageModifier,
                        reduceMotion = reduceMotion,
                        language = AppLanguage.current(),
                        onLanguage = if (AppLanguage.supported) ({ l: AppLanguage -> AppLanguage.apply(l) }) else null,
                    )

                    OnboardingPage.ALLOW -> AllowPage(
                        modifier = pageModifier,
                        items = items,
                        granted = grantedMap,
                        permanentlyDenied = permanentlyDenied,
                        defaultSms = DefaultSmsState(
                            isDefault = isDefault,
                            denied = defaultDenied,
                            unavailable = defaultUnavailable,
                            onRequest = { requestDefault() },
                            onOpenSettings = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ),
                        onRequest = { requestPermissions(it.permissions) },
                        onAllowAll = ::allowAll,
                        onOpenSettings = { context.openAppSettings() },
                    )

                    OnboardingPage.MODE -> ModePage(
                        modifier = pageModifier,
                        mode = mode,
                        onMode = viewModel::setMode,
                        purgeDays = purgeDays,
                        onPurgeDays = viewModel::setPurgeDays,
                        deliveryReports = deliveryReports,
                        onDeliveryReports = viewModel::setDeliveryReports,
                    )

                    OnboardingPage.KEEP_RUNNING ->
                        OemPage(pageModifier, refresh, isDefault, allGranted)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BottomBar(
                page = page,
                pageCount = pages.size,
                nextLabel = nextLabel,
                reduceMotion = reduceMotion,
                onBack = { goTo(page - 1) },
                onNext = { if (current == OnboardingPage.KEEP_RUNNING) onFinish() else goTo(page + 1) },
            )
        }
    }
}

@Composable
private fun BottomBar(
    page: Int,
    pageCount: Int,
    nextLabel: String,
    reduceMotion: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val stepDescription = stringResource(R.string.onb_step_of, page + 1, pageCount)
    // Back, dots, Next. The buttons change width from page to page ("Get started", "Skip", "Finish"), so the dots
    // are laid out on the bar's centre line, and only move off it when a long label would otherwise overlap them.
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = {
            Box {
                if (page > 0) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onb_back))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = stepDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { i -> Dot(selected = i == page, reduceMotion = reduceMotion) }
            }
            Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(nextLabel, maxLines = 1)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val back = measurables[0].measure(loose)
        val dots = measurables[1].measure(loose)
        val next = measurables[2].measure(loose.copy(maxWidth = (width - back.width - dots.width).coerceAtLeast(0)))
        val height = maxOf(back.height, dots.height, next.height)
        val centred = (width - dots.width) / 2
        val dotsX = if (centred >= back.width && centred + dots.width <= width - next.width) {
            centred
        } else {
            back.width + ((width - back.width - next.width - dots.width) / 2).coerceAtLeast(0)
        }
        layout(width, height) {
            back.placeRelative(0, (height - back.height) / 2)
            dots.placeRelative(dotsX, (height - dots.height) / 2)
            next.placeRelative(width - next.width, (height - next.height) / 2)
        }
    }
}

@Composable
private fun Dot(selected: Boolean, reduceMotion: Boolean) {
    val spec = if (reduceMotion) tween<androidx.compose.ui.unit.Dp>(0) else tween(250)
    val width by animateDpAsState(if (selected) 20.dp else 8.dp, spec, label = "dotWidth")
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "dotColor",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .height(8.dp)
            .width(width)
            .clip(CircleShape)
            .background(color)
            .clearAndSetSemantics { },
    )
}

@Composable
internal fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun PageBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One row of the Welcome page's feature list: an icon, a short title and one line of detail. */
internal data class WelcomeFeature(val icon: ImageVector, val titleRes: Int, val detailRes: Int)

/** The Welcome page's features, in the owner-approved order. */
internal fun welcomeFeatures(forwarding: Boolean = true): List<WelcomeFeature> = listOf(
    WelcomeFeature(Symbols.Shield, R.string.onb_welcome_spam, R.string.onb_welcome_spam_desc),
    WelcomeFeature(Symbols.NotificationsActive, R.string.onb_welcome_codes, R.string.onb_welcome_codes_desc),
    WelcomeFeature(Symbols.ContentCopy, R.string.onb_welcome_copy, R.string.onb_welcome_copy_desc),
    WelcomeFeature(Symbols.Rule, R.string.onb_welcome_rules, R.string.onb_welcome_rules_desc),
    WelcomeFeature(Symbols.Forward, R.string.onb_welcome_forwarding, R.string.onb_welcome_forwarding_desc),
    WelcomeFeature(Symbols.ScheduleSend, R.string.onb_welcome_schedule, R.string.onb_welcome_schedule_desc),
    WelcomeFeature(Symbols.Lock, R.string.onb_welcome_lock, R.string.onb_welcome_lock_desc),
    WelcomeFeature(Symbols.Smartphone, R.string.onb_welcome_private, R.string.onb_welcome_private_desc),
).filter { forwarding || it.titleRes != R.string.onb_welcome_forwarding }

/** The three features shown at first; the rest sit behind "More features". */
private fun welcomeTopTitles(): Set<Int> =
    setOf(R.string.onb_welcome_spam, R.string.onb_welcome_codes, R.string.onb_welcome_rules)

/**
 * The first step. The pager page scrolls, so a long list never pushes "Get started" (in the bottom bar) away.
 * The language chip shows when [onLanguage] is set (Android 13+).
 */
@Composable
internal fun WelcomePage(
    modifier: Modifier,
    reduceMotion: Boolean,
    language: AppLanguage = AppLanguage.SYSTEM,
    onLanguage: ((AppLanguage) -> Unit)? = null,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (reduceMotion) tween(0) else tween(600),
        label = "heroAlpha",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onLanguage != null) {
            WelcomeTopRow(language = language, onLanguage = onLanguage)
        } else {
            Spacer(Modifier.height(24.dp))
        }
        AppIconMark(Modifier.alpha(alpha))
        PageTitle(stringResource(R.string.onb_welcome_title))
        PageBody(stringResource(R.string.onb_welcome_intro))
        Spacer(Modifier.height(4.dp))
        var more by rememberSaveable { mutableStateOf(false) }
        val top = welcomeTopTitles()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val forwarding = ForwardingAvailability.isAvailable(LocalContext.current)
            welcomeFeatures(forwarding).filter { more || it.titleRes in top }.forEach { WelcomeFeatureRow(it) }
        }
        TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (more) R.string.ux8f_onb_less else R.string.ux8f_onb_more))
            Icon(if (more) Symbols.ExpandLess else Symbols.ExpandMore, null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WelcomeFeatureRow(feature: WelcomeFeature) {
    // Merged so TalkBack reads the title and its detail as one item; the icon is decorative.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(feature.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(feature.detailRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, text: String) {
    val color = if (ok) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (ok) Symbols.CheckCircleFilled else Symbols.Error, null, tint = color)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OemPage(modifier: Modifier, refresh: Int, isDefault: Boolean, permsOk: Boolean) {
    val context = LocalContext.current
    val oem = remember { OemHelper.detect() }
    val steps = remember { OemHelper.steps(context, oem) }
    val battery = remember(steps) { steps.first { it.kind == OemStepKind.IGNORE_BATTERY } }
    val vendorSteps = remember(steps) {
        steps.filter { it.kind != OemStepKind.IGNORE_BATTERY && it.kind != OemStepKind.APP_INFO }
    }
    // A step whose screen was opened from here and whose state Android cannot report. It is marked done when the user
    // comes back (the next ON_RESUME bumps `refresh`). Saveable, so it survives the process being killed meanwhile.
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var marks by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        pendingKey?.let { key ->
            OnboardingState.setOemStepMarked(context, key, true)
            pendingKey = null
            marks++
        }
    }
    // Re-read on every resume: the user may have changed any of it in Settings.
    val status = refresh.let { OemHelper.backgroundStatus(context) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(stringResource(R.string.onb_oem_title))
        PageBody(stringResource(R.string.ux8f_onb_battery_body))
        BatteryCard(
            done = OemHelper.stateOf(battery.kind, status, markedByUser = false) == StepState.DONE_VERIFIED,
            onOpen = { OemHelper.launch(context, battery) },
        )
        if (vendorSteps.isNotEmpty()) {
            PageBody(stringResource(R.string.ux8f_onb_vendor_hint, stringResource(oem.labelRes)))
        }
        vendorSteps.forEach { step ->
            val key = step.kind.key
            val marked = (marks + refresh).let { OnboardingState.isOemStepMarked(context, key) }
            val state = OemHelper.stateOf(step.kind, status, marked)
            OemStepCard(
                step = step,
                state = state,
                stillLimited = marked && state == StepState.NOT_DONE,
                onOpen = {
                    val opened = OemHelper.launch(context, step) != null
                    if (opened && step.kind.check == StepCheck.USER_CONFIRMED) pendingKey = key
                },
                onUndo = {
                    OnboardingState.setOemStepMarked(context, key, false)
                    marks++
                },
            )
        }
        Text(
            stringResource(R.string.onb_oem_manual_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UnfinishedNote(isDefault, permsOk)
    }
}

/** The one card of the last step: a single button until Android reports uMessage as unrestricted. */
@Composable
private fun BatteryCard(done: Boolean, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (done) {
                StatusRow(ok = true, text = stringResource(R.string.oem2_state_done))
            } else {
                Button(
                    onClick = onOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ux8f_onb_battery_action)) }
            }
        }
    }
}

/**
 * One step. A filled green tick means Android reports it done. A plain tick in a ring means the user did it on a
 * screen Android cannot read (marked when they came back from it), with Undo next to it.
 */
@Composable
private fun OemStepCard(
    step: OemStep,
    state: StepState,
    stillLimited: Boolean,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    val title = stringResource(step.titleRes)
    val stateText = stringResource(
        when {
            state == StepState.DONE_VERIFIED -> R.string.oem2_state_done
            state == StepState.DONE_BY_USER -> R.string.oem2_state_marked
            stillLimited -> R.string.oem2_state_limited
            else -> R.string.oem2_state_todo
        },
    )
    val success = MaterialTheme.colorScheme.success
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = stateText },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(step.descRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    StepState.DONE_VERIFIED -> Icon(Symbols.CheckCircleFilled, null, tint = success)
                    StepState.DONE_BY_USER -> Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(2.dp, success, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Symbols.Check, null, tint = success, modifier = Modifier.size(16.dp))
                    }

                    StepState.NOT_DONE -> Unit
                }
            }
            if (state != StepState.NOT_DONE || stillLimited) {
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (stillLimited) MaterialTheme.colorScheme.error else success,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            when (state) {
                StepState.NOT_DONE -> TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onb_perm_open_settings))
                }

                StepState.DONE_BY_USER -> {
                    val undoDescription = stringResource(R.string.oem2_undo_cd, title)
                    TextButton(
                        onClick = onUndo,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = undoDescription },
                    ) { Text(stringResource(R.string.oem2_undo)) }
                }

                StepState.DONE_VERIFIED -> Unit
            }
        }
    }
}

/** The end of the flow shows only what is still open, so a finished setup adds nothing to read. */
@Composable
private fun UnfinishedNote(isDefault: Boolean, permsOk: Boolean) {
    if (!isDefault || !permsOk) {
        PageBody(stringResource(R.string.ux8f_onb_unfinished))
        if (!isDefault) SummaryRow(stringResource(R.string.onb_done_summary_default), false, null)
        if (!permsOk) SummaryRow(stringResource(R.string.onb_done_summary_permissions), false, null)
    }
}

@Composable
private fun SummaryRow(label: String, ok: Boolean, value: String?) {
    val state = value ?: stringResource(if (ok) R.string.onb_done_ok else R.string.onb_done_todo)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (ok) Symbols.CheckCircleFilled else Symbols.Error,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error,
        )
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(state, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
    }
}

// region Previews

@Preview(name = "Welcome", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun WelcomePagePreview() = UMessageTheme(darkTheme = false) {
    WelcomePage(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), reduceMotion = true)
}

@Preview(
    name = "Welcome dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 1200,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WelcomePageDarkPreview() = UMessageTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
        WelcomePage(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), reduceMotion = true)
    }
}

@Preview(
    name = "Welcome RTL, large text",
    showBackground = true,
    widthDp = 360,
    heightDp = 1600,
    locale = "fa",
    fontScale = 1.3f,
)
@Composable
private fun WelcomePageRtlPreview() = UMessageTheme(darkTheme = false) {
    WelcomePage(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), reduceMotion = true)
}

@Preview(name = "Bottom bar, first page", showBackground = true, widthDp = 360)
@Composable
private fun BottomBarFirstPreview() = UMessageTheme(darkTheme = false) {
    BottomBar(page = 0, pageCount = 4, nextLabel = "Get started", reduceMotion = true, onBack = {}, onNext = {})
}

@Preview(name = "Bottom bar dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BottomBarDarkPreview() = UMessageTheme(darkTheme = true) {
    BottomBar(page = 2, pageCount = 4, nextLabel = "Skip", reduceMotion = true, onBack = {}, onNext = {})
}

@Preview(name = "Bottom bar RTL, large text", showBackground = true, widthDp = 360, locale = "fa", fontScale = 1.3f)
@Composable
private fun BottomBarRtlPreview() = UMessageTheme(darkTheme = false) {
    BottomBar(page = 3, pageCount = 4, nextLabel = "Finish", reduceMotion = true, onBack = {}, onNext = {})
}

// endregion
