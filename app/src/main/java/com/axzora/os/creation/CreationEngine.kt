package com.axzora.os.creation

/**
 * Mr Happy Multimodal Creation Engine — the orchestration core for the
 * "Dream Studio / AI Cinema" pipeline.
 *
 * It turns a natural-language intent into a structured [Project] (world,
 * characters w/ brains, scenes, physics) and walks the production pipeline
 * (Writer → Director → Actors → Cinematography → Editing → Music → Final).
 *
 * Pure + dependency-free so it is unit-testable; the actual image/video/3D/audio
 * generation is performed by model/GPU backends the app wires into the stages.
 */
object CreationEngine {

    enum class Modality { TEXT, IMAGE, VIDEO, AUDIO, WORLD_3D, SIMULATION }

    /** The "digital character brain" — a generated person isn't just a picture. */
    data class CharacterBrain(
        val name: String,
        val personality: String,
        val memory: String,
        val voice: String,
        val emotion: String,
        val movement: String,
        val knowledge: String,
        val goals: String,
    )

    data class Scene(
        val name: String,
        val characters: List<String>,
        val physics: String,
        val audio: String,
    )

    enum class Stage { WRITER, DIRECTOR, ACTORS, CINEMATOGRAPHY, EDITING, MUSIC, FINAL }
    enum class StageStatus { PENDING, IN_PROGRESS, DONE }

    data class PipelineStep(val stage: Stage, val modality: Modality, var status: StageStatus = StageStatus.PENDING)

    data class Project(
        val title: String,
        val intent: Modality,
        val world: String,
        val characters: List<CharacterBrain>,
        val scenes: List<Scene>,
        val physics: String,
        val pipeline: List<PipelineStep>,
    )

    /** A simple intent → project plan builder (a tiny "AI director"). */
    fun plan(prompt: String, modality: Modality = Modality.VIDEO): Project {
        val hero = CharacterBrain("Aria", "warm+curious", "start of story", "soft", "hopeful", "natural", "world-lore", "help")
        return Project(
            title = prompt.ifBlank { "Untitled" },
            intent = modality,
            world = prompt.take(80),
            characters = listOf(hero),
            scenes = listOf(
                Scene("Opening", listOf(hero.name), "gravity=1, cinematic", "ambient+score"),
                Scene("Climax", listOf(hero.name), "gravity=1, dynamic", "score"),
            ),
            physics = "real-world",
            pipeline = listOf(
                PipelineStep(Stage.WRITER, Modality.TEXT),
                PipelineStep(Stage.DIRECTOR, Modality.TEXT),
                PipelineStep(Stage.ACTORS, Modality.VIDEO),
                PipelineStep(Stage.CINEMATOGRAPHY, Modality.VIDEO),
                PipelineStep(Stage.EDITING, Modality.VIDEO),
                PipelineStep(Stage.MUSIC, Modality.AUDIO),
                PipelineStep(Stage.FINAL, Modality.VIDEO),
            ),
        )
    }

    /** Advance the pipeline one stage (simulates/dispatches the next stage). */
    fun advance(project: Project): Project {
        val copy = project.copy(pipeline = project.pipeline.toMutableList())
        val idx = copy.pipeline.indexOfFirst { it.status == StageStatus.PENDING }
        if (idx >= 0) copy.pipeline[idx].status = StageStatus.IN_PROGRESS
        val idxIn = copy.pipeline.indexOfFirst { it.status == StageStatus.IN_PROGRESS }
        if (idxIn >= 0) copy.pipeline[idxIn].status = StageStatus.DONE
        return copy
    }

    /** Which modality a stage targets. */
    fun stageModality(stage: Stage): Modality =
        when (stage) {
            Stage.WRITER, Stage.DIRECTOR -> Modality.TEXT
            Stage.ACTORS, Stage.CINEMATOGRAPHY, Stage.EDITING, Stage.FINAL -> Modality.VIDEO
            Stage.MUSIC -> Modality.AUDIO
        }

    /** Percent complete across the pipeline. */
    fun progress(project: Project): Int {
        if (project.pipeline.isEmpty()) return 0
        val done = project.pipeline.count { it.status == StageStatus.DONE }
        val inProg = project.pipeline.count { it.status == StageStatus.IN_PROGRESS }
        return ((done + inProg * 0.5) / project.pipeline.size * 100).toInt()
    }
}
