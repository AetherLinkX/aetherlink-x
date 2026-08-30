package com.palazik.vpn.di

import android.content.Context
import com.palazik.vpn.data.repository.ProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Lets non-injectable components (e.g. WorkManager workers constructed by the
 * framework) obtain the *singleton* [ProfileRepository] so background updates
 * mutate the same in-memory state the UI observes — instead of a throwaway copy.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryEntryPoint {
    fun profileRepository(): ProfileRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Direct client — used for subscription fetching (v2rayNG: tries proxy first, then direct).
     * No proxy configured here; proxy fallback is done in ProfileRepository.
     */
    @Provides
    @Singleton
    @Named("direct")
    fun provideDirectOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * Proxy client — routes traffic through xray's local SOCKS5 inbound (127.0.0.1:10808).
     *
     * v2rayNG uses this for:
     *  - GET / HEAD real-ping tests so they go through the active proxy profile
     *  - Subscription fetches (first attempt)
     *
     * The proxy is only reachable while palazikVpnService is running. Callers must
     * catch IOException / ConnectException and fall back to the direct client when the
     * service is not active (e.g. during subscription update without VPN running).
     */
    @Provides
    @Singleton
    @Named("proxy")
    fun provideProxyOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * Default (un-named) client kept for backward-compat with any inject site that
     * doesn't yet use @Named.  Points to the direct client.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@Named("direct") client: OkHttpClient): OkHttpClient = client
}
