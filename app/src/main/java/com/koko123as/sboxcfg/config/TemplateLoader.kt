package com.koko123as.sboxcfg.config

import android.content.Context
import com.koko123as.sboxcfg.R
import org.json.JSONObject

/** 加载 res/raw/base_template.json（远程 /custom-config 同款模板，不含 outbounds） */
object TemplateLoader {
    fun load(context: Context): JSONObject {
        context.resources.openRawResource(R.raw.base_template).use { ins ->
            return JSONObject(ins.bufferedReader().readText())
        }
    }
}
