/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2025 Threema GmbH
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

package ch.threema.domain.protocol.urls

import java.net.URLEncoder
import java.util.Locale

class MapPoiNamesUrl(template: String) : ParameterizedUrl(template, requiredPlaceholders = arrayOf("latitude", "longitude", "query")) {
    fun get(latitude: Double, longitude: Double, query: String) = getUrl(
        "latitude" to "%f".format(Locale.US, latitude),
        "longitude" to "%f".format(Locale.US, longitude),
        "query" to URLEncoder.encode(query, "UTF-8").replace("+", "%20"),
    )
}
