package com.glooboo.umessage.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's regex engine rejects some patterns desktop Java accepts, and only `RegexOnDeviceTest` (instrumented)
 * loads the classes that compile one. This guard fails when a source file that builds a Regex or Pattern is not in
 * [holders], or when a class listed there is missing from that device test, so a new regex cannot skip the device
 * check. Note: a regex built inside a function (not in a property) is only class-loaded on the device, not compiled.
 */
class RegexHoldersGuardTest {

    private val base = "com.glooboo.umessage."

    /** Source file under `com/glooboo/umessage/` to the classes that hold or build its regexes. */
    private val holders: Map<String, List<String>> = mapOf(
        "databases/DatabaseBackups.kt" to listOf("databases.DatabaseBackups"),
        "export/ConversationExportJob.kt" to listOf("export.ConversationFileFormat"),
        "export/ExportFileName.kt" to listOf("export.ExportFileName"),
        "extensions/DialIntent.kt" to listOf("extensions.DialIntentKt"),
        "helpers/CodeMasker.kt" to listOf("helpers.CodeMasker"),
        "helpers/LinkExtractor.kt" to listOf("helpers.LinkExtractor"),
        "helpers/MessageCategories.kt" to listOf("helpers.MessageCategories"),
        "helpers/NotificationLinks.kt" to listOf("helpers.NotificationLinksKt"),
        "helpers/OtpCodeExtractor.kt" to listOf("helpers.OtpCodeExtractor"),
        "helpers/OtpCodes.kt" to listOf("helpers.OtpCodes"),
        "helpers/SavedTexts.kt" to listOf("helpers.SavedTexts"),
        "helpers/SmsIntentParser.kt" to listOf("helpers.SmsIntentParser"),
        "helpers/StarredMessages.kt" to listOf("helpers.StarredMessages"),
        "helpers/smartreply/SmartReplyNormalizer.kt" to listOf("helpers.smartreply.SmartReplyNormalizer"),
        "helpers/smartreply/SmartReplyPolicy.kt" to listOf("helpers.smartreply.SmartReplyPolicy"),
        "helpers/smartreply/SmartReplyText.kt" to listOf("helpers.smartreply.SmartReplyText"),
        "helpers/smartreply/engine/EngineReading.kt" to listOf("helpers.smartreply.engine.EngineReading"),
        "spam/DecisionRuleText.kt" to listOf("spam.DecisionRuleText"),
        "spam/KeywordMatch.kt" to listOf("spam.KeywordMatch"),
        "spam/RegexGuard.kt" to listOf("spam.RegexGuard"),
        "spam/TextNormalizer.kt" to listOf("spam.TextNormalizer"),
        "spam/engine/AllowingCode.kt" to listOf("spam.engine.AllowingCode"),
        "spam/engine/DomainMatch.kt" to listOf("spam.engine.DomainMatch"),
        "spam/engine/OtpDetector.kt" to listOf("spam.engine.OtpDetector"),
        "spam/engine/OtpPasswords.kt" to listOf("spam.engine.OtpPasswords"),
        "spam/mms/BlockedMmsStore.kt" to listOf("spam.mms.BlockedMmsStore"),
        "ui/components/NumberText.kt" to listOf("ui.components.NumberTextKt"),
        "ui/components/PhoneNumberDisplay.kt" to listOf("ui.components.PhoneNumberDisplayKt"),
        "ui/components/PhoneNumberListDisplay.kt" to listOf("ui.components.PhoneNumberListDisplayKt"),
        "ui/labels/LabelsState.kt" to listOf("ui.labels.LabelRules"),
        "ui/log/ForwardLogText.kt" to listOf("ui.log.ForwardLogText"),
        "ui/newconversation/GroupRecipients.kt" to listOf("ui.newconversation.GroupRecipientsKt"),
        "ui/spam/BlockedHints.kt" to listOf("ui.spam.BlockedHints"),
        "ui/spam/BlockedNumbers.kt" to listOf("ui.spam.BlockedNumberFormat"),
        "ui/spam/BlockedRestored.kt" to listOf("ui.spam.RestoredLogCodec"),
        "ui/spam/ForwardLogic.kt" to listOf("ui.spam.ForwardTargets"),
        "ui/spam/LegacyBlockLists.kt" to listOf("ui.spam.LegacyMigration"),
        "ui/spam/RuleLogic.kt" to listOf(
            "ui.spam.RuleLogicKt",
            "ui.spam.RuleValidator",
            "ui.spam.RuleSearch",
            "ui.spam.RuleTextCodec",
        ),
        "ui/thread/BankAlertParser.kt" to listOf("ui.thread.BankAlertParser"),
        "ui/thread/LinkSafety.kt" to listOf("ui.thread.LinkSafety"),
        "ui/thread/PhoneMatch.kt" to listOf("ui.thread.PhoneMatchKt"),
        "ui/thread/Tapbacks.kt" to listOf("ui.thread.TapbacksKt"),
        "ui/thread/ThreadLinks.kt" to listOf("ui.thread.ThreadLinksKt"),
        "ui/thread/UnknownCardLogic.kt" to listOf("ui.thread.UnknownCardLogic"),
    )

    private fun module(path: String): File =
        File(path).takeIf { it.exists() } ?: File("app/$path")

    private val builds = Regex("""\bRegex\(|\.toRegex\(|\bPattern\s*\.\s*compile\(""")

    @Test fun everySourceFileThatBuildsARegexIsListed() {
        val found = sortedSetOf<String>()
        for (set in listOf("main", "foss", "gplay")) {
            val root = module("src/$set/kotlin/com/glooboo/umessage")
            if (set == "main") assertTrue("source folder not found: $root", root.isDirectory)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && builds.containsMatchIn(it.readText()) }
                .mapTo(found) { it.relativeTo(root).invariantSeparatorsPath }
        }
        assertEquals(
            "Add the file to holders here and its classes to RegexOnDeviceTest.regexHolders",
            holders.keys.toSortedSet(),
            found,
        )
    }

    @Test fun everyListedClassExistsAndIsInTheDeviceTest() {
        val deviceTest = module("src/androidTest/kotlin/com/glooboo/umessage/spam/RegexOnDeviceTest.kt")
        assertTrue("device test not found: $deviceTest", deviceTest.isFile)
        val text = deviceTest.readText()
        for (name in holders.values.flatten().map { base + it }) {
            assertTrue("$name is missing from RegexOnDeviceTest.regexHolders", text.contains("\"$name\""))
            Class.forName(name, false, javaClass.classLoader)
        }
    }
}
