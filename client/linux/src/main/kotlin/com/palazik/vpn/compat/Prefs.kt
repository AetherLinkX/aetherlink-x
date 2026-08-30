package com.palazik.vpn.compat

import com.palazik.vpn.AppDirs
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * SharedPreferences replacement: one JSON file per pref name under the XDG
 * config dir (~/.config/palazikVPN/<name>.json). Same getString/edit()/apply()
 * surface so the ported repository/viewmodel code reads identically.
 */
class Prefs(name: String) {

    private val file: File = File(AppDirs.configDir, "$name.json")
    private val values = mutableMapOf<String, Any>()

    init {
        if (file.exists()) {
            runCatching {
                val o = JSONObject(file.readText())
                for (key in o.keys()) values[key] = o.get(key)
            }
        }
    }

    @Synchronized
    fun getString(key: String, def: String?): String? = values[key] as? String ?: def

    @Synchronized
    fun getBoolean(key: String, def: Boolean): Boolean = values[key] as? Boolean ?: def

    fun edit(): Editor = Editor()

    inner class Editor {
        private val pending = mutableMapOf<String, Any?>()

        fun putString(key: String, value: String?): Editor = apply { pending[key] = value }
        fun putBoolean(key: String, value: Boolean): Editor = apply { pending[key] = value }

        fun apply() {
            synchronized(this@Prefs) {
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                persist()
            }
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject(values as Map<*, *>).toString(2))
            runCatching {
                Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            }.recoverCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
