package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MusicFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue follows edits only of the list it was built from. Two lists with the same entries (same count, ids and
 * order) are still different lists: moving or removing in the one that is not playing leaves the queue alone.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistQueueTest : ServiceTestBase() {
    private fun file(i: Int) = MusicFile("track %03d.flac".format(i), "${Fx.FX}/Many/Folder %03d/track %03d.flac".format(i, i), false, 0, 0, local)
    private fun queue() = state.playlist.map { it.mediaId.substringAfterLast('/').substringBeforeLast('.').replace("%20", " ") }
    private fun titles(id: String) = state.allPlaylists.first { it.id == id }.items.map { it.title }
    /** The file names (without extension) of playlist [id]. */
    private fun names(id: String) = state.allPlaylists.first { it.id == id }.items.map { it.path.substringAfterLast('/').substringBeforeLast('.') }

    @Test fun editingAnotherListWithTheSameEntriesLeavesTheQueue() {
        val files = (130..133).map { file(it) }
        val names = files.map { it.name.substringBeforeLast('.') }
        main { vm.createPlaylist("queue-A"); vm.createPlaylist("queue-B") }
        waitFor(5_000, "lists") { state.allPlaylists.count { it.name.startsWith("queue-") } == 2 }
        val a = state.allPlaylists.first { it.name == "queue-A" }.id
        val b = state.allPlaylists.first { it.name == "queue-B" }.id
        try {
            main { vm.addFilesToPlaylist(a, files); vm.addFilesToPlaylist(b, files) }
            waitFor(10_000, "entries") { titles(a) == names && titles(b) == names }

            main { vm.playPlaylistSong(a, 0) }
            waitFor(20_000, "queue from A") { state.isPlaying && queue() == names }
            main { vm.playPause() }

            // B shown and reordered: same entries as the queue, but not the list it came from.
            main { vm.switchPlaylist(b) }
            waitFor(5_000, "B shown") { state.activePlaylistId == b }
            main { vm.moveInActivePlaylist(0, 2) }
            waitFor(5_000, "B reordered") { titles(b) == listOf(names[1], names[2], names[0], names[3]) }
            Thread.sleep(1_000)
            assertEquals("queue unchanged by B", names, queue())
            assertEquals("A unchanged", names, titles(a))

            // Removing from a list that is not the queue's source does not touch the queue either.
            main { vm.switchPlaylist("default") }
            waitFor(5_000, "Default shown") { state.activePlaylistId == "default" }
            if (state.activePlaylistItems.isNotEmpty()) {
                main { vm.removeFromActivePlaylist(0) }
                Thread.sleep(1_000)
                assertEquals("queue unchanged by Default", names, queue())
            }

            // A is the queue's source: its moves move the queue.
            main { vm.switchPlaylist(a) }
            waitFor(5_000, "A shown") { state.activePlaylistId == a }
            main { vm.moveInActivePlaylist(0, 2) }
            val moved = listOf(names[1], names[2], names[0], names[3])
            waitFor(5_000, "A reordered") { titles(a) == moved }
            waitFor(5_000, "queue follows A: ${queue()}") { queue() == moved }
        } finally {
            main { vm.deletePlaylist(a); vm.deletePlaylist(b) }
        }
    }

    /**
     * 1.8.0: "Play next" puts a song right after the track playing, "Add to queue" at the end (a folder: its songs); the
     * track playing goes on and "Default" follows. A queue built from one of the user's playlists becomes "Default" with
     * the song, and that playlist keeps its entries.
     */
    @Test fun playNextAndAddToQueue() {
        val files = (140..142).map { file(it) }
        val names = files.map { it.name.substringBeforeLast('.') }
        main { vm.playCustomList(files, 0) }
        waitFor(20_000, "playing") { state.isPlaying && queue() == names }
        val playing = state.currentMediaId

        main { vm.addToQueue(file(150), next = true) }
        waitFor(10_000, "next: ${queue()}") { queue() == listOf(names[0], "track 150", names[1], names[2]) }
        main { vm.addToQueue(file(151), next = false) }
        waitFor(10_000, "end: ${queue()}") { queue() == listOf(names[0], "track 150", names[1], names[2], "track 151") }
        main { vm.addToQueue(MusicFile("Folder 152", "${Fx.FX}/Many/Folder 152", true, 0, 0, local), next = false) }
        waitFor(10_000, "folder at the end: ${queue()}") { queue().size == 6 && queue().last() == "track 152" }
        waitFor(5_000, "Default follows: ${names("default")}") { names("default") == queue() }
        assertEquals("the track playing goes on", playing, state.currentMediaId)
        main { vm.next() }
        waitFor(10_000, "the song put next plays next: ${state.currentMediaId}") { queue().getOrNull(1) != null && state.playlist.getOrNull(1)?.mediaId == state.currentMediaId }

        main { vm.createPlaylist("queue-C") }
        waitFor(5_000, "list") { state.allPlaylists.any { it.name == "queue-C" } }
        val c = state.allPlaylists.first { it.name == "queue-C" }.id
        try {
            main { vm.addFilesToPlaylist(c, (160..161).map { file(it) }) }
            waitFor(10_000, "entries") { names(c) == listOf("track 160", "track 161") }
            main { vm.playPlaylistSong(c, 0) }
            waitFor(20_000, "queue from C") { state.isPlaying && queue() == listOf("track 160", "track 161") }
            main { vm.addToQueue(file(162), next = true) }
            waitFor(10_000, "added: ${queue()}") { queue() == listOf("track 160", "track 162", "track 161") }
            waitFor(5_000, "Default holds the queue") { names("default") == queue() && state.activePlaylistId == "default" }
            assertEquals("C keeps its entries", listOf("track 160", "track 161"), names(c))
        } finally {
            main { vm.playPause(); vm.deletePlaylist(c) }
        }
    }
}
