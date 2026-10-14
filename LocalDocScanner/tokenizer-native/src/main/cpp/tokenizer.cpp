#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

extern "C" JNIEXPORT jlong JNICALL Java_com_localdoc_scanner_tokenizer_SentencePieces_open(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto status = processor->Load(p);
    env->ReleaseStringUTFChars(path, p);
    if (!status.ok()) return 0;
    return reinterpret_cast<jlong>(processor.release());
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_localdoc_scanner_tokenizer_SentencePieces_encode(JNIEnv* env, jobject, jlong handle, jbyteArray text) {
    auto* p = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    if (!p) return nullptr;
    const auto size = env->GetArrayLength(text);
    std::string input(size, '\0');
    env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte*>(input.data()));
    std::vector<std::string> pieces;
    auto status = p->Encode(input, &pieces);
    if (!status.ok()) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "分词失败"); return nullptr; }
    auto array = env->NewObjectArray(pieces.size(), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < pieces.size(); ++i) {
        auto value = env->NewByteArray(pieces[i].size());
        env->SetByteArrayRegion(value, 0, pieces[i].size(), reinterpret_cast<const jbyte*>(pieces[i].data()));
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return array;
}
extern "C" JNIEXPORT void JNICALL Java_com_localdoc_scanner_tokenizer_SentencePieces_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
