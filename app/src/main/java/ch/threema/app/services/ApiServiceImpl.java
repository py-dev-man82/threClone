/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.services;

import org.json.JSONException;

import java.io.IOException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.NonNull;
import ch.threema.app.multidevice.MultiDeviceManager;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.base.ThreemaException;
import ch.threema.domain.models.AppVersion;
import ch.threema.domain.protocol.ServerAddressProvider;
import ch.threema.domain.protocol.api.APIConnector;
import ch.threema.domain.protocol.blob.BlobLoader;
import ch.threema.domain.protocol.blob.BlobScope;
import ch.threema.domain.protocol.blob.BlobUploader;
import ch.threema.domain.stores.TokenStoreInterface;
import okhttp3.OkHttpClient;

public class ApiServiceImpl implements ApiService {
    private final AppVersion appVersion;
    private final boolean useIpv6;
    private final APIConnector apiConnector;
    private final TokenStoreInterface authTokenStore;
    private final ServerAddressProvider serverAddressProvider;
    private final MultiDeviceManager multiDeviceManager;
    private final OkHttpClient baseOkHttpClient;

    public ApiServiceImpl(
        AppVersion appVersion,
        boolean useIpv6,
        APIConnector apiConnector,
        TokenStoreInterface authTokenStore,
        ServerAddressProvider serverAddressProvider,
        MultiDeviceManager multiDeviceManager,
        OkHttpClient baseOkHttpClient
    ) {
        this.appVersion = appVersion;
        this.useIpv6 = useIpv6;
        this.apiConnector = apiConnector;
        this.authTokenStore = authTokenStore;
        this.serverAddressProvider = serverAddressProvider;
        this.multiDeviceManager = multiDeviceManager;
        this.baseOkHttpClient = baseOkHttpClient;
    }

    @NonNull
    @Override
    public BlobUploader createUploader(
        @NonNull byte[] blobData,
        boolean shouldPersist,
        @NonNull BlobScope blobScope
    ) throws ThreemaException {
        final BlobUploader blobUploader;
        if (multiDeviceManager.isMultiDeviceActive()) {
            blobUploader = BlobUploader.mirror(
                baseOkHttpClient,
                ConfigUtils.isOnPremBuild() ? getAuthToken() : null,
                blobData,
                appVersion,
                serverAddressProvider,
                null,
                shouldPersist,
                multiDeviceManager.getPropertiesProvider(),
                blobScope
            );
        } else {
            blobUploader = BlobUploader.usual(
                baseOkHttpClient,
                ConfigUtils.isOnPremBuild() ? getAuthToken() : null,
                blobData,
                appVersion,
                serverAddressProvider,
                null,
                useIpv6,
                shouldPersist
            );
        }
        return blobUploader;
    }

    @NonNull
    @Override
    public BlobLoader createLoader(@NonNull byte[] blobId) {
        BlobLoader loader;
        if (multiDeviceManager.isMultiDeviceActive()) {
            loader = BlobLoader.mirror(
                baseOkHttpClient,
                blobId,
                appVersion,
                serverAddressProvider,
                null,
                multiDeviceManager.getPropertiesProvider()
            );
        } else {
            loader = BlobLoader.usual(
                baseOkHttpClient,
                blobId,
                appVersion,
                serverAddressProvider,
                null,
                useIpv6
            );
        }
        return loader;
    }

    @Override
    public String getAuthToken() throws ThreemaException {
        try {
            return apiConnector.obtainAuthToken(authTokenStore, false);
        } catch (IOException | JSONException e) {
            throw new ThreemaException("Cannot obtain authentication token", e);
        }
    }

    @Override
    public void invalidateAuthToken() {
        this.authTokenStore.storeToken(null);
    }

    @Override
    public HttpsURLConnection createAvatarURLConnection(String identity) throws ThreemaException, IOException {
        URL url = new URL(serverAddressProvider.getAvatarServerUrl(false) + identity);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(ConfigUtils.getSSLSocketFactory(url.getHost()));
        if (ConfigUtils.isOnPremBuild()) {
            connection.setRequestProperty("Authorization", "Token " + getAuthToken());
        }
        return connection;
    }
}
