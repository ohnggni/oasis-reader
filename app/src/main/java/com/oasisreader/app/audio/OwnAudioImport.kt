package com.oasisreader.app.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookOrigin
import com.oasisreader.app.data.Server
import com.oasisreader.app.remote.Remote
import com.oasisreader.app.ui.Notice
import com.oasisreader.app.ui.ShelfActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🎧 소장 오디오북 가져오기 (TODO 30): finds the audiobooks in a 파일 서버 folder or a phone folder and
 * puts them on the 서재. Runs in the app's scope (the dialog may close), tells the result.
 */
object OwnAudioImport {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)
    /** The last choice, offered first next time: 파일 서버 → receive?, 폰 폴더 → copy? */
    fun lastKeep(c: Context, remote: Boolean) = prefs(c).getBoolean(if (remote) "own_keep_remote" else "own_copy_folder", false)
    private fun setLastKeep(c: Context, remote: Boolean, v: Boolean) =
        prefs(c).edit().putBoolean(if (remote) "own_keep_remote" else "own_copy_folder", v).apply()

    private val blank = AudioBook(0, "", null, null, emptyList())

    /** Every audiobook in [dir] of [server] (it may be one itself). [keep]: receive the tracks; else stream (☁️). */
    fun fromRemote(context: Context, server: Server, dir: String, keep: Boolean) {
        val app = context.applicationContext
        setLastKeep(app, true, keep)
        Notice.show(app, "🎧 오디오북을 찾는 중…")
        ShelfActions.scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    Remote.open(app, server).use { fs ->
                        val src = OwnAudio.RemoteSource(server.id, fs)
                        val found = OwnAudio.find(src, OwnAudio.Entry(Remote.nameOf(dir).ifBlank { server.name }, dir, true))
                        found.map { f ->
                            val d = OwnAudio.draft(src, f)
                            val home = f.home.ref
                            OwnAudio.add(
                                app, d,
                                OwnAudio.Own(blank, OwnAudio.REMOTE, serverId = server.id, path = home),
                                key = "remote:${server.id}:$home", keep = keep,
                                origin = BookOrigin.remote(server.id, Remote.parent(home)),
                            )
                        }
                    }
                }
            }
            report(app, r, if (keep) "트랙을 받습니다 (받기 관리)" else "서버에서 바로 듣습니다 (☁️)")
        }
    }

    /** Every audiobook in the phone folder [tree]. [copy]: copy the tracks into the app; else play them in place. */
    fun fromFolder(context: Context, tree: Uri, copy: Boolean) {
        val app = context.applicationContext
        setLastKeep(app, false, copy)
        // kept readable after a restart (in place: always; copy: until the copy is done, in the background)
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Notice.show(app, "🎧 오디오북을 찾는 중…")
        ShelfActions.scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val src = OwnAudio.FolderSource(app, tree)
                    val found = OwnAudio.find(src, src.root())
                    found.map { f ->
                        val d = OwnAudio.draft(src, f) { e -> embedded(app, Uri.parse(e.ref)) }
                        val home = f.home.ref
                        OwnAudio.add(
                            app, d,
                            OwnAudio.Own(blank, OwnAudio.FOLDER, path = home, tree = tree.toString(), inPlace = !copy),
                            key = "folder:$home", keep = copy,
                        )
                    }
                }
            }
            report(app, r, if (copy) "앱 안으로 복사합니다 (받기 관리)" else "폴더에서 그대로 듣습니다")
        }
    }

    /** The picture inside a track (ID3 / MP4), for a cover when the folder has none. */
    private fun embedded(c: Context, uri: Uri): ByteArray? {
        val m = android.media.MediaMetadataRetriever()
        return try { m.setDataSource(c, uri); m.embeddedPicture } catch (e: Exception) { null } finally { runCatching { m.release() } }
    }

    /** [r]: the 서재 ids of the books added. */
    private fun report(c: Context, r: Result<List<String>>, how: String) {
        r.onSuccess { ids ->
            if (ids.isEmpty()) Notice.show(c, "🎧 오디오북을 찾지 못했습니다. 소리 파일(mp3 · m4a · m4b …)이 든 폴더를 골라 주세요.")
            else Notice.added("🎧 오디오북 ${ids.size}권을 서재에 담았습니다. $how", ids.first())
        }.onFailure { Notice.show(c, "🎧 가져오지 못했습니다: ${it.message ?: it.javaClass.simpleName}") }
    }
}

/**
 * Where a 소장 오디오북 plays from: [remote] = 파일 서버 (stream ☁️ / receive), else 폰 폴더
 * (in place / copy). [onPick](keep) — keep = receive / copy.
 */
@Composable
fun OwnAudioChoiceDialog(remote: Boolean, what: String, onPick: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keep by remember { mutableStateOf(OwnAudioImport.lastKeep(context, remote)) }
    val options = if (remote) listOf(
        false to ("☁️ 서버에서 바로 듣기" to "폰 공간을 쓰지 않습니다. 들을 때 서버에 연결되어 있어야 합니다."),
        true to ("⬇️ 폰에 받아서 듣기" to "트랙을 받아 둡니다(암호화). 연결 없이 들을 수 있습니다."),
    ) else listOf(
        false to ("📂 폴더에서 그대로 듣기" to "파일을 옮기지 않습니다. 폴더를 지우거나 옮기면 들을 수 없습니다."),
        true to ("📥 앱 안으로 복사해서 듣기" to "앱 저장공간에 복사합니다(암호화). 원래 폴더는 그대로 둡니다."),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎧 오디오북으로 담기") },
        text = {
            Column {
                Text(
                    "$what 안에서 소리 파일이 든 폴더를 한 권씩 서재에 담습니다. 메타 정보(metadata.json · .nfo)와 표지, m4b 장 표시도 읽습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { keep = v }.padding(top = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = keep == v, onClick = { keep = v })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(label.first, style = MaterialTheme.typography.bodyLarge)
                            Text(label.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(keep) }) { Text("담기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
