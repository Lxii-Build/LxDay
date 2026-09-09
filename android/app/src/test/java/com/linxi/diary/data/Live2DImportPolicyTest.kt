package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DImportPolicyTest {
    @Test
    fun nestedManifestResolvesReferencesRelativeToManifestDirectory() {
        val report = Live2DImportPolicy.analyzeManifest(
            manifestPath = "exports/yumi/yumi.model3.json",
            manifestText = """
                {"FileReferences":{
                  "Moc":"yumi.moc3",
                  "Textures":["textures/texture_00.png"],
                  "Physics":"../shared/yumi.physics3.json"
                }}
            """.trimIndent(),
            availableEntries = setOf(
                "exports/yumi/yumi.model3.json",
                "exports/yumi/yumi.moc3",
                "exports/yumi/textures/texture_00.png",
                "exports/shared/yumi.physics3.json",
            ),
        )

        assertTrue(report.canRender)
        assertEquals(
            listOf("exports/yumi/yumi.moc3", "exports/yumi/textures/texture_00.png"),
            report.references.filter { it.requiredForFirstFrame }.map { it.path },
        )
        assertTrue(report.missingOptional.isEmpty())
    }

    @Test
    fun vtubeSidecarIsMetadataAndMissingMocIsBlocking() {
        val report = Live2DImportPolicy.analyzeManifest(
            manifestPath = "yumi.model3.json",
            manifestText = """{"FileReferences":{"Moc":"yumi.moc3","Textures":["yumi.8192/texture_00.png"]}}""",
            availableEntries = setOf(
                "yumi.model3.json",
                "yumi.8192/texture_00.png",
                "yumi.vtube.json",
            ),
            textureInfo = mapOf(
                "yumi.8192/texture_00.png" to Live2DImportPolicy.TextureInfo(8192, 8192),
            ),
            hasVtubeStudioConfig = true,
        )

        assertFalse(report.canRender)
        assertTrue(report.missingRequired.contains("yumi.moc3"))
        assertTrue(report.textureWarnings.single().contains("8192"))
        assertTrue(report.warnings.any { it.contains("VTube Studio") })
        assertTrue(report.userMessage().contains("moc3"))
    }

    @Test
    fun caseCollisionAndTraversalAreRejectedAtEntryBoundary() {
        assertEquals("models/model3.json", Live2DImportPolicy.normalizeEntry("models\\model3.json"))
        assertEquals(null, Live2DImportPolicy.normalizeEntry("../model3.json"))
        assertEquals(null, Live2DImportPolicy.normalizeEntry("/model3.json"))
        assertEquals(
            Live2DImportPolicy.collisionKey("Yumi.PNG"),
            Live2DImportPolicy.collisionKey("yumi.png"),
        )
    }

    @Test
    fun aggregateTextureBudgetIsCheckedAfterPerTextureChecks() {
        val report = Live2DImportPolicy.analyzeManifest(
            manifestPath = "model.model3.json",
            manifestText = """{"FileReferences":{"Moc":"model.moc3","Textures":["a.png","b.png","c.png"]}}""",
            availableEntries = setOf("model.model3.json", "model.moc3", "a.png", "b.png", "c.png"),
            textureInfo = mapOf(
                "a.png" to Live2DImportPolicy.TextureInfo(4096, 2048),
                "b.png" to Live2DImportPolicy.TextureInfo(4096, 2048),
                "c.png" to Live2DImportPolicy.TextureInfo(1, 1),
            ),
        )

        assertTrue(report.textureWarnings.any { it.contains("合计") })
        assertFalse(report.canRender)
    }

    @Test
    fun moc3PreflightRejectsPlainTextButDoesNotPretendToBeCoreValidation() {
        assertFalse(Live2DImportPolicy.hasMoc3Header("moc".encodeToByteArray()))
        assertTrue(Live2DImportPolicy.hasMoc3Header(byteArrayOf('M'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), '3'.code.toByte(), 1, 0, 0, 0)))
    }
}
