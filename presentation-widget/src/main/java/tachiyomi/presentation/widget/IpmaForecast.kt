package tachiyomi.presentation.widget

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Previsão diária do IPMA (API pública, sem key) para o Porto, que é a localidade do IPMA mais perto de Gaia.
 * A última resposta fica guardada para o widget ter o que mostrar sem rede.
 */
object IpmaForecast {

    data class Day(
        val date: LocalDate,
        val min: Int,
        val max: Int,
        val weatherType: Int,
        val description: String,
        val rainProbability: Int,
        val windDirection: String,
    )

    fun load(context: Context): List<Day> {
        val prefs = context.getSharedPreferences("aoi_ipma", Context.MODE_PRIVATE)
        val fresh = try {
            val forecast = get(FORECAST_URL)
            val types = get(TYPES_URL)
            prefs.edit {
                putString(KEY_FORECAST, forecast)
                putString(KEY_TYPES, types)
            }
            forecast to types
        } catch (e: Exception) {
            null
        }
        val (forecast, types) = fresh
            ?: (prefs.getString(KEY_FORECAST, null) to prefs.getString(KEY_TYPES, null))
        if (forecast == null) return emptyList()
        return try {
            parse(forecast, types)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("IPMA respondeu ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(forecast: String, types: String?): List<Day> {
        val descriptions = mutableMapOf<Int, String>()
        if (types != null) {
            val list = JSONObject(types).getJSONArray("data")
            for (i in 0 until list.length()) {
                val t = list.getJSONObject(i)
                descriptions[t.getInt("idWeatherType")] = t.getString("descWeatherTypePT")
            }
        }
        val today = LocalDate.now()
        val data: JSONArray = JSONObject(forecast).getJSONArray("data")
        return (0 until data.length())
            .map { data.getJSONObject(it) }
            .map { d ->
                val type = d.getInt("idWeatherType")
                Day(
                    date = LocalDate.parse(d.getString("forecastDate")),
                    min = d.getString("tMin").toDouble().let(Math::round).toInt(),
                    max = d.getString("tMax").toDouble().let(Math::round).toInt(),
                    weatherType = type,
                    description = descriptions[type].orEmpty(),
                    rainProbability = d.getString("precipitaProb").toDouble().let(Math::round).toInt(),
                    windDirection = d.optString("predWindDir"),
                )
            }
            .filter { !it.date.isBefore(today) }
    }

    /** Símbolo do tipo de tempo do IPMA (weather-type-classe.json). */
    fun emoji(weatherType: Int): String = when (weatherType) {
        1 -> "☀️"
        2, 25 -> "🌤️"
        3 -> "⛅"
        4, 24, 27 -> "☁️"
        5 -> "🌥️"
        6, 7, 12, 13 -> "🌦️"
        8, 9, 10, 11, 14, 15 -> "🌧️"
        16, 17, 26 -> "🌫️"
        18, 21, 22, 28, 29, 30 -> "🌨️"
        19, 20, 23 -> "⛈️"
        else -> "🌡️"
    }

    const val PAGE_URL = "https://www.ipma.pt/pt/otempo/prev.localidade.hora/#Porto&Porto"
    private const val PORTO = 1131200
    private const val FORECAST_URL = "https://api.ipma.pt/open-data/forecast/meteorology/cities/daily/$PORTO.json"
    private const val TYPES_URL = "https://api.ipma.pt/open-data/weather-type-classe.json"
    private const val KEY_FORECAST = "forecast"
    private const val KEY_TYPES = "types"
}
