/* SPDX-License-Identifier: MIT
 * Android adapter for Leptonica's BSD-2-Clause single-page text-line dewarping.
 * No GPL/AGPL implementation is used. All engine source remains in third_party/leptonica.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include "allheaders.h"

JNIEXPORT jint JNICALL Java_com_localdoc_scanner_cv_BookDewarp_flattenNative(
        JNIEnv *env, jobject self, jobject input, jobject output) {
    (void)self;
    AndroidBitmapInfo src, dst;
    void *src_pixels = NULL, *dst_pixels = NULL;
    PIX *page = NULL, *result = NULL;
    L_DEWARPA *models = NULL;
    jint status = -1;
    if (AndroidBitmap_getInfo(env, input, &src) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &dst) != ANDROID_BITMAP_RESULT_SUCCESS ||
        src.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format != src.format ||
        src.width != dst.width || src.height != dst.height || src.width < 64 || src.height < 64 ||
        (uint64_t)src.width * src.height > 6000000) return -1;
    page = pixCreate((l_int32)src.width, (l_int32)src.height, 32);
    if (!page) goto cleanup;
    if (AndroidBitmap_lockPixels(env, input, &src_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) goto cleanup;
    for (uint32_t y = 0; y < src.height; ++y) {
        const uint8_t *row = (const uint8_t *)src_pixels + y * src.stride;
        l_uint32 *line = pixGetData(page) + y * pixGetWpl(page);
        for (uint32_t x = 0; x < src.width; ++x) {
            composeRGBPixel(row[4*x], row[4*x+1], row[4*x+2], &line[x]);
        }
    }
    AndroidBitmap_unlockPixels(env, input);
    src_pixels = NULL;
    pixSetResolution(page, 200, 200);
    if (dewarpSinglePage(page, 180, 1, 1, 1, &result, &models, 0) != 0 || !result || !models) goto cleanup;
    L_DEWARP *model = dewarpaGetDewarp(models, 0);
    // Upstream returns a copy for an unreliable model. Do not label that as successful flattening.
    if (!model || !model->vvalid || pixGetWidth(result) != (l_int32)dst.width ||
            pixGetHeight(result) != (l_int32)dst.height || pixGetDepth(result) != 32) {
        status = 0;
        goto cleanup;
    }
    if (AndroidBitmap_lockPixels(env, output, &dst_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) goto cleanup;
    for (uint32_t y = 0; y < dst.height; ++y) {
        uint8_t *row = (uint8_t *)dst_pixels + y * dst.stride;
        l_uint32 *line = pixGetData(result) + y * pixGetWpl(result);
        for (uint32_t x = 0; x < dst.width; ++x) {
            l_int32 red, green, blue;
            extractRGBValues(line[x], &red, &green, &blue);
            row[4*x] = (uint8_t)red; row[4*x+1] = (uint8_t)green;
            row[4*x+2] = (uint8_t)blue; row[4*x+3] = 255;
        }
    }
    status = 1;
cleanup:
    if (src_pixels) AndroidBitmap_unlockPixels(env, input);
    if (dst_pixels) AndroidBitmap_unlockPixels(env, output);
    pixDestroy(&result);
    pixDestroy(&page);
    dewarpaDestroy(&models);
    return status;
}
