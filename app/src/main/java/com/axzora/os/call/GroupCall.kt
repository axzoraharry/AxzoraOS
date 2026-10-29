package com.axzora.os.call

import org.webrtc.VideoTrack

/**
 * One other person in a group call.
 *
 * [connected] is what the tile shows as "waiting" or live, and it is per
 * person rather than per call: in a mesh each connection comes up on its own,
 * so someone on a slow network is still connecting while the rest are already
 * talking.
 */
data class GroupPerson(
    val uid: String,
    val name: String,
    val connected: Boolean = false,
    val video: VideoTrack? = null,
    /**
     * True once a frame has actually arrived.
     *
     * A video channel exists for everyone from the start so cameras can be
     * switched on mid-call, so the presence of a track says nothing about
     * whether there is a picture. Only frames do.
     */
    val hasVideo: Boolean = false,
)

/** A group call in progress on this phone. */
data class GroupInfo(
    val active: Boolean = false,
    val roomId: String = "",
    val title: String = "Group call",
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val video: Boolean = false,
    val frontCamera: Boolean = true,
    /** Set by the first connection to come up, so the timer starts once. */
    val startedAt: Long = 0L,
    val error: String = "",
    val people: List<GroupPerson> = emptyList(),
) {
    /** Nobody through yet — the group is still ringing. */
    val connecting: Boolean get() = active && people.none { it.connected }
}
