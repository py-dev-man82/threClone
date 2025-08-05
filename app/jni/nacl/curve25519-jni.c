/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema Java Client
 * Copyright (c) 2015-2020 Threema GmbH
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

#include <string.h>
#include <jni.h>

int crypto_scalarmult_curve25519(uint8_t *mypublic, const uint8_t *secret, const uint8_t *basepoint);

JNIEXPORT jint JNICALL Java_com_neilalexander_jnacl_crypto_curve25519_crypto_1scalarmult_1native(JNIEnv* env, jclass cls,
	jbyteArray qarr, jbyteArray narr, jbyteArray parr) {

	jbyte q[32], n[32], p[32];
	int res;

	(*env)->GetByteArrayRegion(env, narr, 0, 32, n);
	(*env)->GetByteArrayRegion(env, parr, 0, 32, p);

	res = crypto_scalarmult_curve25519((unsigned char *)q, (unsigned char *)n, (unsigned char *)p);

	(*env)->SetByteArrayRegion(env, qarr, 0, 32, q);

	return res;
}
