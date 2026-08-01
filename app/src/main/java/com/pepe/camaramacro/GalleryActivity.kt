package com.pepe.camaramacro

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.pepe.camaramacro.databinding.ActivityGalleryBinding
import com.pepe.camaramacro.databinding.ItemGalleryBinding
import com.pepe.camaramacro.databinding.ItemGalleryGridBinding
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
    private var pendingDeleteUri: Uri? = null

    data class GalleryItem(
        val id: Long,
        val uri: Uri,
        val isVideo: Boolean,
        val dateAdded: Long,
        val mime: String
    )

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) pendingDeleteUri?.let { onDeletedByUri(it) }
            pendingDeleteUri = null
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadAndShow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingDeleteUri = savedInstanceState?.getString(KEY_PENDING_DELETE)?.let { Uri.parse(it) }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateCounter()
        })
        binding.btnClose.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
        binding.btnGrid.setOnClickListener { toggleGrid() }

        requestMediaPermsThenLoad()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDeleteUri?.let { outState.putString(KEY_PENDING_DELETE, it.toString()) }
    }

    // ---------------------------------------------------------------- Permisos

    private fun readPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun requestMediaPermsThenLoad() {
        val missing = readPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) loadAndShow() else permLauncher.launch(missing.toTypedArray())
    }

    private fun loadAndShow() {
        loadMedia()
        binding.pager.adapter = Adapter()
        binding.grid.layoutManager = GridLayoutManager(this, 3)
        binding.grid.adapter = GridAdapter()
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isNotEmpty()) binding.pager.setCurrentItem(start, false)
        applyEmptyState()
        updateCounter()
    }

    private var gridShown = false

    private fun toggleGrid() {
        if (items.isEmpty()) return
        gridShown = !gridShown
        binding.grid.visibility = if (gridShown) View.VISIBLE else View.GONE
        binding.pager.visibility = if (gridShown) View.GONE else View.VISIBLE
        binding.bottomBar.visibility = if (gridShown || !chromeVisible) View.GONE else View.VISIBLE
        binding.counter.visibility = if (gridShown) View.INVISIBLE else View.VISIBLE
        if (gridShown) {
            binding.grid.adapter?.notifyDataSetChanged()
            binding.grid.scrollToPosition(binding.pager.currentItem)
        }
    }

    private fun openAt(index: Int) {
        gridShown = false
        binding.grid.visibility = View.GONE
        binding.pager.visibility = View.VISIBLE
        binding.counter.visibility = View.VISIBLE
        chromeVisible = true
        binding.pager.setCurrentItem(index, false)
        applyEmptyState()
        updateCounter()
    }

    private fun applyEmptyState() {
        val empty = items.isEmpty()
        binding.emptyLabel.visibility = if (empty) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (empty || !chromeVisible) View.GONE else View.VISIBLE
    }

    private fun updateCounter() {
        binding.counter.text =
            if (items.isEmpty()) "" else "${binding.pager.currentItem + 1} / ${items.size}"
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        binding.topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        applyEmptyState()
    }

    // ---------------------------------------------------------------- Datos

    private fun loadMedia() {
        items.clear()
        // DCIM/Camera es donde guardamos ahora (para que Google Photos las indexe), pero
        // seguimos leyendo las carpetas antiguas para no perder las fotos ya tomadas.
        queryInto(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "DCIM/Camera")
        queryInto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "DCIM/Camera")
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
            // Anclado al inicio: no atrapa "CamaraMacro2" ni otras carpetas.
            // PELIGRO EVITADO: en SQL el guion bajo es COMODIN, asi que 'VID_%' casaba
            // tambien con los VID_20250801_... de la camara de fabrica, que viven en la
            // MISMA carpeta DCIM/Camera. El boton Borrar quedaba a un toque de los videos
            // personales del usuario. Ahora se filtra por PROPIETARIO (solo lo que creo
            // esta app) y los LIKE van escapados como respaldo para carpetas antiguas.
            // El guion bajo se escapa con '#' (mas legible en Kotlin que la barra invertida).
            val porNombre = "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'MACRO#_%' ESCAPE '#' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'VID#_%' ESCAPE '#')"
            selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? OR $porNombre)"
            } else {
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND $porNombre"
            }
            args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("$relPath/%", packageName)
            } else {
                arrayOf("$relPath/%")
            }
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/$relPath/%")
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
            Log.e("CamMacro", "Galería query falló: ${e.message}")
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
            Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCurrent() {
        val item = items.getOrNull(binding.pager.currentItem) ?: return
        try {
            val rows = contentResolver.delete(item.uri, null, null)
            if (rows > 0) onDeletedByUri(item.uri)
            else Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Android 10+: el sistema pide confirmación del usuario para borrar.
            try {
                val sender = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                        MediaStore.createDeleteRequest(contentResolver, listOf(item.uri)).intentSender
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                        e.userAction.actionIntent.intentSender
                    else -> null
                }
                if (sender != null) {
                    pendingDeleteUri = item.uri
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
                }
            } catch (e2: Exception) {
                pendingDeleteUri = null
                Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** Borra por URI (no por índice): siempre quita lo que el sistema confirmó. */
    private fun onDeletedByUri(uri: Uri) {
        val i = items.indexOfFirst { it.uri == uri }
        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
        if (i < 0) return
        items.removeAt(i)
        binding.pager.adapter?.notifyItemRemoved(i)
        if (items.isNotEmpty()) {
            val newPos = i.coerceIn(0, items.size - 1)
            binding.pager.setCurrentItem(newPos, false)
        }
        applyEmptyState()
        updateCounter()
    }

    /** Reproduce el vídeo DENTRO de la app en vez de derivar a otra aplicación. */
    private fun playInline(holder: VH, item: GalleryItem) {
        val v = holder.b.pageVideo
        holder.b.playBadge.visibility = View.GONE
        holder.b.pageImage.visibility = View.GONE
        v.visibility = View.VISIBLE
        try {
            v.setVideoURI(item.uri)
            val mc = android.widget.MediaController(this)
            mc.setAnchorView(v)
            v.setMediaController(mc)
            v.setOnPreparedListener { it.isLooping = false; v.start() }
            v.setOnCompletionListener { stopInline(holder) }
            v.setOnErrorListener { _, _, _ ->
                // Si el códec falla, ofrecemos abrirlo fuera en vez de dejar pantalla negra.
                stopInline(holder)
                openExternally(item)
                true
            }
            v.requestFocus()
        } catch (e: Exception) {
            stopInline(holder)
            openExternally(item)
        }
    }

    private fun stopInline(holder: VH) {
        val v = holder.b.pageVideo
        try { if (v.isPlaying) v.stopPlayback() } catch (e: Exception) {}
        v.setMediaController(null)
        v.visibility = View.GONE
        holder.b.pageImage.visibility = View.VISIBLE
        holder.b.playBadge.visibility =
            if (items.getOrNull(holder.bindingAdapterPosition)?.isVideo == true) View.VISIBLE else View.GONE
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
            holder.b.pageImage.setImageDrawable(null)
            holder.b.pageImage.onTap = { toggleChrome() }
            holder.b.pageImage.onZoomChanged = { z -> binding.pager.isUserInputEnabled = !z }
            if (item.isVideo) {
                holder.b.playBadge.visibility = View.VISIBLE
                holder.b.playBadge.setOnClickListener { playInline(holder, item) }
                loadVideoThumb(item.uri, holder.b.pageImage)
            } else {
                holder.b.playBadge.visibility = View.GONE
                holder.b.playBadge.setOnClickListener(null)
                holder.b.pageImage.tag = null
                holder.b.pageImage.load(item.uri)
            }
        }

        override fun onViewRecycled(holder: VH) {
            stopInline(holder)
            // Evita arrastrar la miniatura del item anterior al reciclar.
            holder.b.pageImage.tag = null
            holder.b.pageImage.setImageDrawable(null)
            binding.pager.isUserInputEnabled = true
        }
    }

    private inner class VH(val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root)

    // ---------------------------------------------------------------- Carrete (grid)

    private inner class GridAdapter : RecyclerView.Adapter<GVH>() {
        private val cell = resources.displayMetrics.widthPixels / 3

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GVH {
            val b = ItemGalleryGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.layoutParams = b.root.layoutParams.apply { height = cell }
            return GVH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: GVH, position: Int) {
            val item = items[position]
            holder.b.cellPlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.b.cellImage.tag = null
            if (item.isVideo) {
                loadVideoThumb(item.uri, holder.b.cellImage)
            } else {
                holder.b.cellImage.load(item.uri)
            }
            holder.b.root.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) openAt(p)
            }
        }
    }

    private inner class GVH(val b: ItemGalleryGridBinding) : RecyclerView.ViewHolder(b.root)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (gridShown) toggleGrid() else super.onBackPressed()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val KEY_PENDING_DELETE = "pendingDeleteUri"
    }
}
