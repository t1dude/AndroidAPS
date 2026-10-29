package app.aaps.core.interfaces.maintenance

import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.rx.weardata.CwfFile
import java.io.File

interface FileListProvider : PrefsFileInfo {

    val resultPath: File
    fun ensurePreferenceDirExists(): DocumentFile?
    fun ensureExportDirExists(): DocumentFile?
    fun ensureTempDirExists(): DocumentFile?
    fun ensureExtraDirExists(): DocumentFile?

    /**
     * The folder for the files of a handover between the phone and the watch: the pump session copy, the
     * session the watch hands back, and the watch's test log. Kept apart from the exports.
     */
    fun ensureHandoverDirExists(): DocumentFile?

    fun newPreferenceFile(): DocumentFile?
    fun newExportCsvFile(): DocumentFile?
    fun newCwfFile(filename: String, withDate: Boolean = true): DocumentFile?

    fun ensureResultDirExists(): File
    fun newResultFile(): File
    fun listCustomWatchfaceFiles(): MutableList<CwfFile>
    fun checkMetadata(metadata: Map<PrefsMetadataKey, PrefMetadata>): Map<PrefsMetadataKey, PrefMetadata>
}