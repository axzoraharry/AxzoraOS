package com.axzora.os.creation

import com.axzora.os.creation.CreationEngine.CharacterBrain
import com.axzora.os.creation.CreationEngine.Modality
import com.axzora.os.creation.CreationEngine.Stage
import com.axzora.os.creation.CreationEngine.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Mr Happy Multimodal Creation Engine. */
class CreationEngineTest {

    @Test
    fun planBuildsFullPipelineInOrder() {
        val p = CreationEngine.plan("Futuristic Hyderabad 2050", Modality.VIDEO)
        assertEquals(
            listOf(Stage.WRITER, Stage.DIRECTOR, Stage.ACTORS, Stage.CINEMATOGRAPHY, Stage.EDITING, Stage.MUSIC, Stage.FINAL),
            p.pipeline.map { it.stage },
        )
        assertTrue(p.characters.isNotEmpty())
        assertTrue(p.scenes.size >= 2)
        assertEquals("real-world", p.physics)
    }

    @Test
    fun characterBrainHoldsFullDigitalBrain() {
        val c = CharacterBrain("Aria", "warm", "mem", "voice", "hopeful", "natural", "know", "help")
        assertEquals("warm", c.personality)
        assertEquals("mem", c.memory)
        assertEquals("help", c.goals)
    }

    @Test
    fun advanceCompletesOneStagePerCall() {
        var p = CreationEngine.plan("test")
        p = CreationEngine.advance(p)
        assertEquals(StageStatus.DONE, p.pipeline.first { it.stage == Stage.WRITER }.status)
        assertEquals(StageStatus.PENDING, p.pipeline.first { it.stage == Stage.DIRECTOR }.status)
        p = CreationEngine.advance(p)
        assertEquals(StageStatus.DONE, p.pipeline.first { it.stage == Stage.DIRECTOR }.status)
    }

    @Test
    fun stageModalityMapsCorrectly() {
        assertEquals(Modality.TEXT, CreationEngine.stageModality(Stage.WRITER))
        assertEquals(Modality.VIDEO, CreationEngine.stageModality(Stage.ACTORS))
        assertEquals(Modality.AUDIO, CreationEngine.stageModality(Stage.MUSIC))
    }

    @Test
    fun progressRisesAsStagesComplete() {
        var p = CreationEngine.plan("x")
        assertEquals(0, CreationEngine.progress(p))
        repeat(7) { p = CreationEngine.advance(p) }
        assertEquals(100, CreationEngine.progress(p))
    }
}
