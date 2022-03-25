package com.github.shadowsocks.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.util.Base64
import com.github.shadowsocks.Core
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.utils.setUA
import com.github.shadowsocks.utils.useCancellable
import java.io.IOException
import java.net.*
import java.sql.SQLException

object SSRSubManager {

    @Throws(SQLException::class)
    fun createSSRSub(ssrSub: SSRSub): SSRSub {
        ssrSub.id = 0
        ssrSub.id = PrivateDatabase.ssrSubDao.create(ssrSub)
        return ssrSub
    }

    @Throws(SQLException::class)
    fun updateSSRSub(ssrSub: SSRSub) = check(PrivateDatabase.ssrSubDao.update(ssrSub) == 1)

    @Throws(IOException::class)
    fun getSSRSub(id: Long): SSRSub? = try {
        PrivateDatabase.ssrSubDao[id]
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(SQLException::class)
    fun delSSRSub(id: Long) {
        check(PrivateDatabase.ssrSubDao.delete(id) == 1)
    }

    @Throws(IOException::class)
    fun getAllSSRSub(): List<SSRSub> = try {
        PrivateDatabase.ssrSubDao.getAll()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        emptyList()
    }

    private suspend fun getResponse(url: String, useProxy: Boolean): String {
        val proxy = when {
            DataStore.socksAddress == null -> Proxy.NO_PROXY
            useProxy -> Proxy(Proxy.Type.SOCKS, DataStore.socksAddress)
            DataStore.serviceMode != Key.modeVpn -> Proxy(Proxy.Type.SOCKS, DataStore.proxyAddress)
            else -> Proxy.NO_PROXY
        }
        if (DataStore.socksUser.isNotEmpty()) {
            val authenticator = object : Authenticator() {
                override fun getPasswordAuthentication() =
                        PasswordAuthentication(DataStore.socksUser, DataStore.socksPswd)
            }
            Authenticator.setDefault(authenticator)
        }
        try {
            val connection = URL(url).openConnection(proxy) as HttpURLConnection
            val body = connection.setUA().useCancellable {
                inputStream.bufferedReader().use { it.readText() }
            }
            return String(Base64.decode(body, Base64.URL_SAFE))
        } finally {
            Authenticator.setDefault(null)
        }
    }

    fun deletProfiles(ssrSub: SSRSub) {
        val profiles = ProfileManager.getAllProfilesByGroup(ssrSub.url_group)
        ProfileManager.deletSSRSubProfiles(profiles)
    }

    suspend fun update(ssrSub: SSRSub, b: String = "", useProxy: Boolean) {
        val response = b.ifEmpty { getResponse(ssrSub.url, useProxy) }
        var profiles = Profile.findAllSSRUrls(response, Core.currentProfile?.main).toList()
        when {
            profiles.isEmpty() -> {
                deletProfiles(ssrSub)
                ssrSub.status = SSRSub.EMPTY
                updateSSRSub(ssrSub)
                return
            }
            ssrSub.url_group != profiles[0].url_group -> {
                ssrSub.status = SSRSub.NAME_CHANGED
                updateSSRSub(ssrSub)
                return
            }
            else -> {
                ssrSub.status = SSRSub.NORMAL
                updateSSRSub(ssrSub)
            }
        }

        val count = profiles.count()
        var limit = -1
        if (response.indexOf("MAX=") == 0) {
            limit = response.split("\n")[0].split("MAX=")[1]
                    .replace("\\D+".toRegex(), "").toInt()
        }
        if (limit != -1 && limit < count) {
            profiles = profiles.shuffled().take(limit)
        }

        ProfileManager.createProfilesFromSub(profiles, ssrSub.url_group)
    }

    suspend fun create(url: String, useProxy: Boolean): SSRSub {
        val response = getResponse(url, useProxy)
        val profiles = Profile.findAllSSRUrls(response, Core.currentProfile?.main).toList()
        if (profiles.isNullOrEmpty() || profiles[0].url_group.isEmpty()) throw IOException("Invalid Link")
        var new = SSRSub(url = url, url_group = profiles[0].url_group)
        getAllSSRSub().forEach { if (it.url_group == new.url_group) throw IOException("Group already exists") }
        new = createSSRSub(new)
        update(new, response, useProxy)
        return new
    }
}
