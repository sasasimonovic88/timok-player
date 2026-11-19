package rs.timok.player.epg

import rs.timok.player.data.db.ProgramEntity

/**
 * Makes one channel's programme list non-overlapping, for the guide grid and the channel schedule.
 *
 * Overlaps come from the same programme arriving from two guides (server and a built-in guide) with starts a few
 * minutes apart, and from sloppy end times. A programme mostly covered by the previous one is a duplicate and is
 * dropped; one that only starts a little early keeps its place but begins where the previous one ends.
 */
fun cleanSchedule(list: List<ProgramEntity>): List<ProgramEntity> {
    val out = ArrayList<ProgramEntity>(list.size)
    for (p in list.sortedBy { it.startMs }) {
        val last = out.lastOrNull()
        if (last == null || p.startMs >= last.endMs) {
            out += p
            continue
        }
        val duration = (p.endMs - p.startMs).coerceAtLeast(1)
        val overlap = last.endMs - p.startMs
        when {
            p.endMs <= last.endMs -> Unit
            overlap * 2 >= duration -> Unit
            else -> out += p.copy(startMs = last.endMs)
        }
    }
    return out
}
