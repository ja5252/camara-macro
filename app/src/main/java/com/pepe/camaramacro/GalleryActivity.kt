package com.pepe.camaramacro

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.pepe.camaramacro.databinding.ActivityGalleryBinding
import com.pepe.camaramacro.databinding.ItemGalleryBinding
import java.util.concurrent.Executors

/**
 * Galería integrada: pasa fotos y videos (carpetas CamaraMacro) deslizando,
 * con compartir, borrar y reproducir video. Sin salir de la app.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val items = ArrayList<GalleryItem>()
    private val bg = Executors.newSingleThreadExecutor()
    private var chromeVisible = true
    private var pendingDeletePos: Int? = null

    data class GalleryItem(
        val id: Long,
        val uri: Uri,
        val isVideo: Boolean,
        val dateAdded: Long,
        val mime: String
    )

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) pendingDeletePos?.let { onDeleted(it) }
            pendingDeletePos = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadMedia()
        if (items.isEmpty()) {
            binding.emptyLabel.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.GONE
        }

        binding.pager.adapter = Adapter()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateCounter()
        })
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        binding.pager.setCurrentItem(start, false)
        updateCounter()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
    }

    private fun updateCounter() {
        binding.counter.text = if (items.isEmpty()) "" else "${binding.pager.currentItem + 1} / ${items.size}"
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val v = if (chromeVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        if (items.isNotEmpty()) binding.bottomBar.visibility = v
    }

    // ---------------------------------------------------------------- Datos

    private fun loadMedia() {
        items.clear()
        queryInto(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures/CamaraMacro")
        queryInto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies/CamaraMacro")
        items.sortByDescending { it.dateAdded }
    }

    private fun queryInto(collection: Uri, isVideo: Boolean, relPath: String) {
        val idCol = MediaStore.MediaColumns._ID
        val dateCol = MediaStore.MediaColumns.DATE_ADDED
        val mimeCol = MediaStore.MediaColumns.MIME_TYPE
        val projection = arrayOf(idCol, dateCol, mimeCol)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%$relPath%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%$relPath%")
        }
        try {
            contentResolver.query(collection, projection, selection, args, "$dateCol DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(idCol)
                val iDate = c.getColumnIndexOrThrow(dateCol)
                val iMime = c.getColumnIndexOrThrow(mimeCol)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    items.add(
                        GalleryItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            isVideo = isVideo,
                            dateAdded = c.getLong(iDate),
                            mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*"
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- Acciones

    private fun shareCurrent() {
        val item = items.getOrNull(binding.pager.currentItem) ?: return
        try {
            val send = Intent(Intent.ACTION_SEND)
                .setType(item.mime)
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (e: Exception) {
        }
    }

    private fun openExternally(item: GalleryItem) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(item.uri, item.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
        }
    }

    private fun deleteCurrent() {
        val pos = binding.pager.currentItem
        val item = items.getOrNull(pos) ?: return
        try {
            val rows = contentResolver.delete(item.uri, null, null)
            if (rows > 0) onDeleted(pos)
            else Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Android 10+: el sistema pide confirmación del usuario para borrar.
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(item.uri)).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) {
                pendingDeletePos = pos
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleted(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        items.removeAt(pos)
        binding.pager.adapter?.notifyItemRemoved(pos)
        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
        if (items.isEmpty()) {
            binding.emptyLabel.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.GONE
        }
        updateCounter()
    }

    private fun loadVideoThumb(uri: Uri, target: android.widget.ImageView) {
        target.setImageDrawable(null)
        target.tag = uri
        bg.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(1280, 1280), null)
                } else {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this, uri)
                    val b = r.frameAtTime
                    r.release()
                    b
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (bmp != null && target.tag == uri) target.setImageBitmap(bmp) }
        }
    }

    // ---------------------------------------------------------------- Adapter

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.pageImage.setOnClickListener { toggleChrome() }
            if (item.isVideo) {
                holder.b.playBadge.visibility = View.VISIBLE
                holder.b.playBadge.setOnClickListener { openExternally(item) }
                loadVideoThumb(item.uri, holder.b.pageImage)
            } else {
                holder.b.playBadge.visibility = View.GONE
                holder.b.pageImage.tag = null
                holder.b.pageImage.load(item.uri)
            }
        }
    }

    private inner class VH(val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
