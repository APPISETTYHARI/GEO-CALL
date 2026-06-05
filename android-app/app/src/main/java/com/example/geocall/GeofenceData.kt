package com.example.geocall

import org.json.JSONArray

data class GeofenceData(
    val id: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
    val contactName: String,
    val phoneNumber: String,
    val enabled: Boolean,
    var triggered: Boolean = false
) {
    companion object {
        fun fromJsonArray(jsonString: String): List<GeofenceData> {
            val list = mutableListOf<GeofenceData>()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    GeofenceData(
                        id = obj.getString("id"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng"),
                        radius = obj.getDouble("radius"),
                        contactName = obj.getString("contactName"),
                        phoneNumber = obj.getString("phoneNumber"),
                        enabled = obj.optBoolean("enabled", true),
                        triggered = false
                    )
                )
            }
            return list
        }
    }
}
