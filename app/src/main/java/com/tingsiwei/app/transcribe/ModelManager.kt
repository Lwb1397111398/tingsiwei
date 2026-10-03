package com.tingsiwei.app.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 离线模型下载/管理（ASR 识别模型 + TTS 朗读模型）。
 * 默认从 hf-mirror.com（国内镜像）下载，支持断点续传：中断后再点下载会从已下载的字节继续。
 */
object ModelManager {

    // ---------- ASR（SenseVoice） ----------

    private const val ASR_REPO =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main"
    const val MODEL_URL = "$ASR_REPO/model.int8.onnx"
    const val TOKENS_URL = "$ASR_REPO/tokens.txt"
    const val MODEL_BYTES = 237_115_547L

    // ---------- TTS（kokoro-int8-multi-lang-v1_1，中英双语，默认音色 zf_001 中文女声） ----------
    // 清单即仓库实况（2026-09 核对，大文件在前、espeak 语音数据垫底）：字节数兼作完整性下限，
    // 下载完尺寸不符会报错。int8 量化版比 fp32 省一半空间、手机推理快数倍，音色比 vits-melo 自然得多。
    private const val TTS_REPO =
        "https://hf-mirror.com/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/main"

    /** 一个待下载文件：仓库内相对路径（可能带子目录）+ 预期字节数 */
    data class TtsFile(val relPath: String, val bytes: Long)

    val TTS_FILES: List<TtsFile> = listOf(
        TtsFile("model.int8.onnx", 114299010L),
        TtsFile("voices.bin", 53790720L),
        TtsFile("lexicon-zh.txt", 2119465L),
        TtsFile("lexicon-us-en.txt", 5956885L),
        TtsFile("lexicon-gb-en.txt", 6366635L),
        TtsFile("date-zh.fst", 59154L),
        TtsFile("number-zh.fst", 64482L),
        TtsFile("phone-zh.fst", 88630L),
        TtsFile("dict/hmm_model.utf8", 519739L),
        TtsFile("dict/idf.utf8", 5998717L),
        TtsFile("dict/jieba.dict.utf8", 5071204L),
        TtsFile("dict/pos_dict/char_state_tab.utf8", 327139L),
        TtsFile("dict/pos_dict/prob_emit.utf8", 1687686L),
        TtsFile("dict/pos_dict/prob_start.utf8", 4347L),
        TtsFile("dict/pos_dict/prob_trans.utf8", 124159L),
        TtsFile("dict/stop_words.utf8", 8974L),
        TtsFile("dict/user.dict.utf8", 829194L),
        TtsFile("tokens.txt", 1111L),
        TtsFile("espeak-ng-data/af_dict", 121473L),
        TtsFile("espeak-ng-data/am_dict", 63878L),
        TtsFile("espeak-ng-data/an_dict", 6691L),
        TtsFile("espeak-ng-data/ar_dict", 478165L),
        TtsFile("espeak-ng-data/as_dict", 5005L),
        TtsFile("espeak-ng-data/az_dict", 43773L),
        TtsFile("espeak-ng-data/ba_dict", 2098L),
        TtsFile("espeak-ng-data/be_dict", 2652L),
        TtsFile("espeak-ng-data/bg_dict", 87051L),
        TtsFile("espeak-ng-data/bn_dict", 89979L),
        TtsFile("espeak-ng-data/bpy_dict", 5226L),
        TtsFile("espeak-ng-data/bs_dict", 47068L),
        TtsFile("espeak-ng-data/ca_dict", 45566L),
        TtsFile("espeak-ng-data/chr_dict", 2859L),
        TtsFile("espeak-ng-data/cmn_dict", 1566335L),
        TtsFile("espeak-ng-data/cs_dict", 49645L),
        TtsFile("espeak-ng-data/cv_dict", 1344L),
        TtsFile("espeak-ng-data/cy_dict", 43130L),
        TtsFile("espeak-ng-data/da_dict", 245287L),
        TtsFile("espeak-ng-data/de_dict", 68276L),
        TtsFile("espeak-ng-data/el_dict", 72841L),
        TtsFile("espeak-ng-data/en_dict", 166944L),
        TtsFile("espeak-ng-data/eo_dict", 4666L),
        TtsFile("espeak-ng-data/es_dict", 49252L),
        TtsFile("espeak-ng-data/et_dict", 44263L),
        TtsFile("espeak-ng-data/eu_dict", 48841L),
        TtsFile("espeak-ng-data/fa_dict", 292423L),
        TtsFile("espeak-ng-data/fi_dict", 43928L),
        TtsFile("espeak-ng-data/fr_dict", 63727L),
        TtsFile("espeak-ng-data/ga_dict", 52673L),
        TtsFile("espeak-ng-data/gd_dict", 49121L),
        TtsFile("espeak-ng-data/gn_dict", 3248L),
        TtsFile("espeak-ng-data/grc_dict", 3433L),
        TtsFile("espeak-ng-data/gu_dict", 82480L),
        TtsFile("espeak-ng-data/hak_dict", 3335L),
        TtsFile("espeak-ng-data/haw_dict", 2443L),
        TtsFile("espeak-ng-data/he_dict", 6963L),
        TtsFile("espeak-ng-data/hi_dict", 92143L),
        TtsFile("espeak-ng-data/hr_dict", 49388L),
        TtsFile("espeak-ng-data/ht_dict", 1803L),
        TtsFile("espeak-ng-data/hu_dict", 153785L),
        TtsFile("espeak-ng-data/hy_dict", 62263L),
        TtsFile("espeak-ng-data/ia_dict", 331275L),
        TtsFile("espeak-ng-data/id_dict", 43458L),
        TtsFile("espeak-ng-data/intonations", 2040L),
        TtsFile("espeak-ng-data/io_dict", 2165L),
        TtsFile("espeak-ng-data/is_dict", 44354L),
        TtsFile("espeak-ng-data/it_dict", 152889L),
        TtsFile("espeak-ng-data/ja_dict", 47652L),
        TtsFile("espeak-ng-data/jbo_dict", 2243L),
        TtsFile("espeak-ng-data/ka_dict", 87775L),
        TtsFile("espeak-ng-data/kk_dict", 1859L),
        TtsFile("espeak-ng-data/kl_dict", 2838L),
        TtsFile("espeak-ng-data/kn_dict", 87828L),
        TtsFile("espeak-ng-data/ko_dict", 47523L),
        TtsFile("espeak-ng-data/kok_dict", 6394L),
        TtsFile("espeak-ng-data/ku_dict", 2265L),
        TtsFile("espeak-ng-data/ky_dict", 64977L),
        TtsFile("espeak-ng-data/la_dict", 3806L),
        TtsFile("espeak-ng-data/lang/aav/vi", 111L),
        TtsFile("espeak-ng-data/lang/aav/vi-VN-x-central", 143L),
        TtsFile("espeak-ng-data/lang/aav/vi-VN-x-south", 142L),
        TtsFile("espeak-ng-data/lang/art/eo", 41L),
        TtsFile("espeak-ng-data/lang/art/ia", 29L),
        TtsFile("espeak-ng-data/lang/art/io", 50L),
        TtsFile("espeak-ng-data/lang/art/jbo", 69L),
        TtsFile("espeak-ng-data/lang/art/lfn", 135L),
        TtsFile("espeak-ng-data/lang/art/piqd", 56L),
        TtsFile("espeak-ng-data/lang/art/py", 140L),
        TtsFile("espeak-ng-data/lang/art/qdb", 57L),
        TtsFile("espeak-ng-data/lang/art/qya", 173L),
        TtsFile("espeak-ng-data/lang/art/sjn", 175L),
        TtsFile("espeak-ng-data/lang/azc/nci", 114L),
        TtsFile("espeak-ng-data/lang/bat/lt", 28L),
        TtsFile("espeak-ng-data/lang/bat/ltg", 312L),
        TtsFile("espeak-ng-data/lang/bat/lv", 229L),
        TtsFile("espeak-ng-data/lang/bnt/sw", 41L),
        TtsFile("espeak-ng-data/lang/bnt/tn", 42L),
        TtsFile("espeak-ng-data/lang/ccs/ka", 124L),
        TtsFile("espeak-ng-data/lang/cel/cy", 37L),
        TtsFile("espeak-ng-data/lang/cel/ga", 66L),
        TtsFile("espeak-ng-data/lang/cel/gd", 51L),
        TtsFile("espeak-ng-data/lang/cus/om", 39L),
        TtsFile("espeak-ng-data/lang/dra/kn", 55L),
        TtsFile("espeak-ng-data/lang/dra/ml", 57L),
        TtsFile("espeak-ng-data/lang/dra/ta", 51L),
        TtsFile("espeak-ng-data/lang/dra/te", 70L),
        TtsFile("espeak-ng-data/lang/esx/kl", 30L),
        TtsFile("espeak-ng-data/lang/eu", 54L),
        TtsFile("espeak-ng-data/lang/gmq/da", 43L),
        TtsFile("espeak-ng-data/lang/gmq/is", 27L),
        TtsFile("espeak-ng-data/lang/gmq/nb", 87L),
        TtsFile("espeak-ng-data/lang/gmq/sv", 25L),
        TtsFile("espeak-ng-data/lang/gmw/af", 123L),
        TtsFile("espeak-ng-data/lang/gmw/de", 42L),
        TtsFile("espeak-ng-data/lang/gmw/en", 140L),
        TtsFile("espeak-ng-data/lang/gmw/en-029", 335L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-scotland", 295L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-gbclan", 238L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-gbcwmd", 188L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-rp", 249L),
        TtsFile("espeak-ng-data/lang/gmw/en-US", 257L),
        TtsFile("espeak-ng-data/lang/gmw/en-US-nyc", 271L),
        TtsFile("espeak-ng-data/lang/gmw/lb", 31L),
        TtsFile("espeak-ng-data/lang/gmw/nl", 23L),
        TtsFile("espeak-ng-data/lang/grk/el", 23L),
        TtsFile("espeak-ng-data/lang/grk/grc", 99L),
        TtsFile("espeak-ng-data/lang/inc/as", 42L),
        TtsFile("espeak-ng-data/lang/inc/bn", 25L),
        TtsFile("espeak-ng-data/lang/inc/bpy", 39L),
        TtsFile("espeak-ng-data/lang/inc/gu", 42L),
        TtsFile("espeak-ng-data/lang/inc/hi", 23L),
        TtsFile("espeak-ng-data/lang/inc/kok", 26L),
        TtsFile("espeak-ng-data/lang/inc/mr", 41L),
        TtsFile("espeak-ng-data/lang/inc/ne", 37L),
        TtsFile("espeak-ng-data/lang/inc/or", 39L),
        TtsFile("espeak-ng-data/lang/inc/pa", 25L),
        TtsFile("espeak-ng-data/lang/inc/sd", 66L),
        TtsFile("espeak-ng-data/lang/inc/si", 55L),
        TtsFile("espeak-ng-data/lang/inc/ur", 94L),
        TtsFile("espeak-ng-data/lang/ine/hy", 61L),
        TtsFile("espeak-ng-data/lang/ine/hyw", 365L),
        TtsFile("espeak-ng-data/lang/ine/sq", 103L),
        TtsFile("espeak-ng-data/lang/ira/fa", 90L),
        TtsFile("espeak-ng-data/lang/ira/fa-Latn", 269L),
        TtsFile("espeak-ng-data/lang/ira/ku", 40L),
        TtsFile("espeak-ng-data/lang/iro/chr", 569L),
        TtsFile("espeak-ng-data/lang/itc/la", 297L),
        TtsFile("espeak-ng-data/lang/jpx/ja", 52L),
        TtsFile("espeak-ng-data/lang/ko", 51L),
        TtsFile("espeak-ng-data/lang/map/haw", 42L),
        TtsFile("espeak-ng-data/lang/miz/mto", 183L),
        TtsFile("espeak-ng-data/lang/myn/quc", 210L),
        TtsFile("espeak-ng-data/lang/poz/id", 134L),
        TtsFile("espeak-ng-data/lang/poz/mi", 367L),
        TtsFile("espeak-ng-data/lang/poz/ms", 430L),
        TtsFile("espeak-ng-data/lang/qu", 88L),
        TtsFile("espeak-ng-data/lang/roa/an", 27L),
        TtsFile("espeak-ng-data/lang/roa/ca", 25L),
        TtsFile("espeak-ng-data/lang/roa/es", 63L),
        TtsFile("espeak-ng-data/lang/roa/es-419", 167L),
        TtsFile("espeak-ng-data/lang/roa/fr", 79L),
        TtsFile("espeak-ng-data/lang/roa/fr-BE", 84L),
        TtsFile("espeak-ng-data/lang/roa/fr-CH", 86L),
        TtsFile("espeak-ng-data/lang/roa/ht", 140L),
        TtsFile("espeak-ng-data/lang/roa/it", 109L),
        TtsFile("espeak-ng-data/lang/roa/pap", 62L),
        TtsFile("espeak-ng-data/lang/roa/pt", 95L),
        TtsFile("espeak-ng-data/lang/roa/pt-BR", 109L),
        TtsFile("espeak-ng-data/lang/roa/ro", 26L),
        TtsFile("espeak-ng-data/lang/sai/gn", 47L),
        TtsFile("espeak-ng-data/lang/sem/am", 41L),
        TtsFile("espeak-ng-data/lang/sem/ar", 50L),
        TtsFile("espeak-ng-data/lang/sem/he", 40L),
        TtsFile("espeak-ng-data/lang/sem/mt", 41L),
        TtsFile("espeak-ng-data/lang/sit/cmn", 686L),
        TtsFile("espeak-ng-data/lang/sit/cmn-Latn-pinyin", 161L),
        TtsFile("espeak-ng-data/lang/sit/hak", 128L),
        TtsFile("espeak-ng-data/lang/sit/my", 56L),
        TtsFile("espeak-ng-data/lang/sit/yue", 194L),
        TtsFile("espeak-ng-data/lang/sit/yue-Latn-jyutping", 213L),
        TtsFile("espeak-ng-data/lang/tai/shn", 92L),
        TtsFile("espeak-ng-data/lang/tai/th", 37L),
        TtsFile("espeak-ng-data/lang/trk/az", 45L),
        TtsFile("espeak-ng-data/lang/trk/ba", 25L),
        TtsFile("espeak-ng-data/lang/trk/cv", 40L),
        TtsFile("espeak-ng-data/lang/trk/kk", 40L),
        TtsFile("espeak-ng-data/lang/trk/ky", 43L),
        TtsFile("espeak-ng-data/lang/trk/nog", 39L),
        TtsFile("espeak-ng-data/lang/trk/tk", 25L),
        TtsFile("espeak-ng-data/lang/trk/tr", 25L),
        TtsFile("espeak-ng-data/lang/trk/tt", 23L),
        TtsFile("espeak-ng-data/lang/trk/ug", 24L),
        TtsFile("espeak-ng-data/lang/trk/uz", 39L),
        TtsFile("espeak-ng-data/lang/urj/et", 237L),
        TtsFile("espeak-ng-data/lang/urj/fi", 237L),
        TtsFile("espeak-ng-data/lang/urj/hu", 73L),
        TtsFile("espeak-ng-data/lang/urj/smj", 45L),
        TtsFile("espeak-ng-data/lang/zle/be", 52L),
        TtsFile("espeak-ng-data/lang/zle/ru", 57L),
        TtsFile("espeak-ng-data/lang/zle/ru-LV", 280L),
        TtsFile("espeak-ng-data/lang/zle/ru-cl", 91L),
        TtsFile("espeak-ng-data/lang/zle/uk", 97L),
        TtsFile("espeak-ng-data/lang/zls/bg", 111L),
        TtsFile("espeak-ng-data/lang/zls/bs", 230L),
        TtsFile("espeak-ng-data/lang/zls/hr", 262L),
        TtsFile("espeak-ng-data/lang/zls/mk", 28L),
        TtsFile("espeak-ng-data/lang/zls/sl", 43L),
        TtsFile("espeak-ng-data/lang/zls/sr", 250L),
        TtsFile("espeak-ng-data/lang/zlw/cs", 23L),
        TtsFile("espeak-ng-data/lang/zlw/pl", 38L),
        TtsFile("espeak-ng-data/lang/zlw/sk", 24L),
        TtsFile("espeak-ng-data/lb_dict", 687931L),
        TtsFile("espeak-ng-data/lfn_dict", 2793L),
        TtsFile("espeak-ng-data/lt_dict", 49890L),
        TtsFile("espeak-ng-data/lv_dict", 66337L),
        TtsFile("espeak-ng-data/mi_dict", 1346L),
        TtsFile("espeak-ng-data/mk_dict", 63859L),
        TtsFile("espeak-ng-data/ml_dict", 92345L),
        TtsFile("espeak-ng-data/mr_dict", 87391L),
        TtsFile("espeak-ng-data/ms_dict", 53541L),
        TtsFile("espeak-ng-data/mt_dict", 4384L),
        TtsFile("espeak-ng-data/mto_dict", 3960L),
        TtsFile("espeak-ng-data/my_dict", 95948L),
        TtsFile("espeak-ng-data/nci_dict", 1534L),
        TtsFile("espeak-ng-data/ne_dict", 95377L),
        TtsFile("espeak-ng-data/nl_dict", 65979L),
        TtsFile("espeak-ng-data/no_dict", 4178L),
        TtsFile("espeak-ng-data/nog_dict", 3294L),
        TtsFile("espeak-ng-data/om_dict", 2302L),
        TtsFile("espeak-ng-data/or_dict", 89246L),
        TtsFile("espeak-ng-data/pa_dict", 79953L),
        TtsFile("espeak-ng-data/pap_dict", 2128L),
        TtsFile("espeak-ng-data/phondata", 550424L),
        TtsFile("espeak-ng-data/phondata-manifest", 21821L),
        TtsFile("espeak-ng-data/phonindex", 39074L),
        TtsFile("espeak-ng-data/phontab", 55796L),
        TtsFile("espeak-ng-data/piqd_dict", 1710L),
        TtsFile("espeak-ng-data/pl_dict", 76730L),
        TtsFile("espeak-ng-data/pt_dict", 67817L),
        TtsFile("espeak-ng-data/py_dict", 2409L),
        TtsFile("espeak-ng-data/qdb_dict", 3028L),
        TtsFile("espeak-ng-data/qu_dict", 1919L),
        TtsFile("espeak-ng-data/quc_dict", 1450L),
        TtsFile("espeak-ng-data/qya_dict", 1939L),
        TtsFile("espeak-ng-data/ro_dict", 68538L),
        TtsFile("espeak-ng-data/ru_dict", 8532392L),
        TtsFile("espeak-ng-data/sd_dict", 59928L),
        TtsFile("espeak-ng-data/shn_dict", 88172L),
        TtsFile("espeak-ng-data/si_dict", 85384L),
        TtsFile("espeak-ng-data/sjn_dict", 1783L),
        TtsFile("espeak-ng-data/sk_dict", 50002L),
        TtsFile("espeak-ng-data/sl_dict", 45047L),
        TtsFile("espeak-ng-data/smj_dict", 35095L),
        TtsFile("espeak-ng-data/sq_dict", 45003L),
        TtsFile("espeak-ng-data/sr_dict", 46832L),
        TtsFile("espeak-ng-data/sv_dict", 47836L),
        TtsFile("espeak-ng-data/sw_dict", 47804L),
        TtsFile("espeak-ng-data/ta_dict", 209553L),
        TtsFile("espeak-ng-data/te_dict", 94837L),
        TtsFile("espeak-ng-data/th_dict", 2301L),
        TtsFile("espeak-ng-data/tk_dict", 20868L),
        TtsFile("espeak-ng-data/tn_dict", 3072L),
        TtsFile("espeak-ng-data/tr_dict", 46793L),
        TtsFile("espeak-ng-data/tt_dict", 2121L),
        TtsFile("espeak-ng-data/ug_dict", 2070L),
        TtsFile("espeak-ng-data/uk_dict", 3492L),
        TtsFile("espeak-ng-data/ur_dict", 133556L),
        TtsFile("espeak-ng-data/uz_dict", 2540L),
        TtsFile("espeak-ng-data/vi_dict", 52608L),
        TtsFile("espeak-ng-data/voices/!v/Alex", 128L),
        TtsFile("espeak-ng-data/voices/!v/Alicia", 474L),
        TtsFile("espeak-ng-data/voices/!v/Andrea", 357L),
        TtsFile("espeak-ng-data/voices/!v/Andy", 320L),
        TtsFile("espeak-ng-data/voices/!v/Annie", 315L),
        TtsFile("espeak-ng-data/voices/!v/AnxiousAndy", 361L),
        TtsFile("espeak-ng-data/voices/!v/Demonic", 3858L),
        TtsFile("espeak-ng-data/voices/!v/Denis", 305L),
        TtsFile("espeak-ng-data/voices/!v/Diogo", 379L),
        TtsFile("espeak-ng-data/voices/!v/Gene", 281L),
        TtsFile("espeak-ng-data/voices/!v/Gene2", 283L),
        TtsFile("espeak-ng-data/voices/!v/Henrique", 381L),
        TtsFile("espeak-ng-data/voices/!v/Hugo", 378L),
        TtsFile("espeak-ng-data/voices/!v/Jacky", 267L),
        TtsFile("espeak-ng-data/voices/!v/Lee", 338L),
        TtsFile("espeak-ng-data/voices/!v/Marco", 467L),
        TtsFile("espeak-ng-data/voices/!v/Mario", 270L),
        TtsFile("espeak-ng-data/voices/!v/Michael", 270L),
        TtsFile("espeak-ng-data/voices/!v/Mike", 112L),
        TtsFile("espeak-ng-data/voices/!v/Mr serious", 3193L),
        TtsFile("espeak-ng-data/voices/!v/Nguyen", 280L),
        TtsFile("espeak-ng-data/voices/!v/Reed", 202L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax", 233L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax2", 435L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax3", 435L),
        TtsFile("espeak-ng-data/voices/!v/Storm", 420L),
        TtsFile("espeak-ng-data/voices/!v/Tweaky", 3189L),
        TtsFile("espeak-ng-data/voices/!v/UniRobot", 417L),
        TtsFile("espeak-ng-data/voices/!v/adam", 75L),
        TtsFile("espeak-ng-data/voices/!v/anika", 493L),
        TtsFile("espeak-ng-data/voices/!v/anikaRobot", 512L),
        TtsFile("espeak-ng-data/voices/!v/announcer", 300L),
        TtsFile("espeak-ng-data/voices/!v/antonio", 381L),
        TtsFile("espeak-ng-data/voices/!v/aunty", 358L),
        TtsFile("espeak-ng-data/voices/!v/belinda", 340L),
        TtsFile("espeak-ng-data/voices/!v/benjamin", 201L),
        TtsFile("espeak-ng-data/voices/!v/boris", 224L),
        TtsFile("espeak-ng-data/voices/!v/caleb", 57L),
        TtsFile("espeak-ng-data/voices/!v/croak", 93L),
        TtsFile("espeak-ng-data/voices/!v/david", 112L),
        TtsFile("espeak-ng-data/voices/!v/ed", 287L),
        TtsFile("espeak-ng-data/voices/!v/edward", 151L),
        TtsFile("espeak-ng-data/voices/!v/edward2", 152L),
        TtsFile("espeak-ng-data/voices/!v/f1", 324L),
        TtsFile("espeak-ng-data/voices/!v/f2", 357L),
        TtsFile("espeak-ng-data/voices/!v/f3", 375L),
        TtsFile("espeak-ng-data/voices/!v/f4", 350L),
        TtsFile("espeak-ng-data/voices/!v/f5", 432L),
        TtsFile("espeak-ng-data/voices/!v/fast", 149L),
        TtsFile("espeak-ng-data/voices/!v/grandma", 263L),
        TtsFile("espeak-ng-data/voices/!v/grandpa", 256L),
        TtsFile("espeak-ng-data/voices/!v/gustave", 253L),
        TtsFile("espeak-ng-data/voices/!v/ian", 3168L),
        TtsFile("espeak-ng-data/voices/!v/iven", 261L),
        TtsFile("espeak-ng-data/voices/!v/iven2", 279L),
        TtsFile("espeak-ng-data/voices/!v/iven3", 262L),
        TtsFile("espeak-ng-data/voices/!v/iven4", 261L),
        TtsFile("espeak-ng-data/voices/!v/john", 3186L),
        TtsFile("espeak-ng-data/voices/!v/kaukovalta", 361L),
        TtsFile("espeak-ng-data/voices/!v/klatt", 38L),
        TtsFile("espeak-ng-data/voices/!v/klatt2", 38L),
        TtsFile("espeak-ng-data/voices/!v/klatt3", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt4", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt5", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt6", 39L),
        TtsFile("espeak-ng-data/voices/!v/linda", 350L),
        TtsFile("espeak-ng-data/voices/!v/m1", 335L),
        TtsFile("espeak-ng-data/voices/!v/m2", 264L),
        TtsFile("espeak-ng-data/voices/!v/m3", 300L),
        TtsFile("espeak-ng-data/voices/!v/m4", 290L),
        TtsFile("espeak-ng-data/voices/!v/m5", 262L),
        TtsFile("espeak-ng-data/voices/!v/m6", 188L),
        TtsFile("espeak-ng-data/voices/!v/m7", 254L),
        TtsFile("espeak-ng-data/voices/!v/m8", 284L),
        TtsFile("espeak-ng-data/voices/!v/marcelo", 251L),
        TtsFile("espeak-ng-data/voices/!v/max", 225L),
        TtsFile("espeak-ng-data/voices/!v/michel", 404L),
        TtsFile("espeak-ng-data/voices/!v/miguel", 382L),
        TtsFile("espeak-ng-data/voices/!v/mike2", 188L),
        TtsFile("espeak-ng-data/voices/!v/norbert", 3189L),
        TtsFile("espeak-ng-data/voices/!v/pablo", 3142L),
        TtsFile("espeak-ng-data/voices/!v/paul", 284L),
        TtsFile("espeak-ng-data/voices/!v/pedro", 352L),
        TtsFile("espeak-ng-data/voices/!v/quincy", 354L),
        TtsFile("espeak-ng-data/voices/!v/rob", 265L),
        TtsFile("espeak-ng-data/voices/!v/robert", 274L),
        TtsFile("espeak-ng-data/voices/!v/robosoft", 451L),
        TtsFile("espeak-ng-data/voices/!v/robosoft2", 454L),
        TtsFile("espeak-ng-data/voices/!v/robosoft3", 455L),
        TtsFile("espeak-ng-data/voices/!v/robosoft4", 447L),
        TtsFile("espeak-ng-data/voices/!v/robosoft5", 445L),
        TtsFile("espeak-ng-data/voices/!v/robosoft6", 287L),
        TtsFile("espeak-ng-data/voices/!v/robosoft7", 410L),
        TtsFile("espeak-ng-data/voices/!v/robosoft8", 243L),
        TtsFile("espeak-ng-data/voices/!v/sandro", 530L),
        TtsFile("espeak-ng-data/voices/!v/shelby", 280L),
        TtsFile("espeak-ng-data/voices/!v/steph", 364L),
        TtsFile("espeak-ng-data/voices/!v/steph2", 367L),
        TtsFile("espeak-ng-data/voices/!v/steph3", 377L),
        TtsFile("espeak-ng-data/voices/!v/travis", 383L),
        TtsFile("espeak-ng-data/voices/!v/victor", 253L),
        TtsFile("espeak-ng-data/voices/!v/whisper", 186L),
        TtsFile("espeak-ng-data/voices/!v/whisperf", 392L),
        TtsFile("espeak-ng-data/voices/!v/zac", 275L),
        TtsFile("espeak-ng-data/yue_dict", 563571L),
    )

    /** 全部文件的总字节数（约 215MB），用作整体进度分母 */
    val TTS_TOTAL_BYTES: Long = TTS_FILES.sumOf { it.bytes }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- ASR 文件状态 ----------

    private fun finalFile(context: Context, name: String): File =
        File(SherpaTranscriber.modelDir(context), name)

    private fun tmpFile(context: Context, name: String): File =
        File(SherpaTranscriber.modelDir(context), "$name.part")

    fun downloadedBytes(context: Context, name: String): Long {
        val done = finalFile(context, name)
        if (done.exists()) return done.length()
        val part = tmpFile(context, name)
        return if (part.exists()) part.length() else 0
    }

    fun isFileComplete(context: Context, name: String, minBytes: Long): Boolean =
        finalFile(context, name).length() >= minBytes

    /** 下载单个文件到 ASR 模型目录（断点续传）。onProgress(已下载, 总大小)。 */
    suspend fun downloadFile(
        context: Context,
        name: String,
        url: String,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Unit = downloadFileTo(SherpaTranscriber.modelDir(context), name, url, minBytes, onProgress)

    // ---------- 通用下载 ----------

    /** 下载单个文件到任意目录（断点续传）。relPath 可带子目录（自动建父目录）。 */
    suspend fun downloadFileTo(
        dir: File,
        relPath: String,
        url: String,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val dst = File(dir, relPath)
        dst.parentFile?.mkdirs()
        if (dst.length() >= minBytes) {
            onProgress(dst.length(), dst.length())
            return@withContext
        }
        val part = File(dir, "$relPath.part")
        var attempts = 0
        while (true) {
            attempts++
            try {
                var start = if (part.exists()) part.length() else 0
                val reqBuilder = Request.Builder().url(url)
                if (start > 0) reqBuilder.header("Range", "bytes=$start-")
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        throw Exception("下载失败 (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw Exception("下载失败：空响应")
                    val append = resp.code == 206 && start > 0
                    if (!append) {
                        start = 0
                        if (part.exists()) part.delete()
                    }
                    val total = body.contentLength().let {
                        if (it > 0) it + start else maxOf(start, minBytes)
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(part, append).use { fos ->
                            copyWithProgress(input, fos, start, total, onProgress)
                        }
                    }
                }
                if (part.length() < minBytes) throw Exception("下载的文件不完整")
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                return@withContext
            } catch (e: Exception) {
                if (attempts >= 5) throw e
                kotlinx.coroutines.delay(1500L * attempts)
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        out: FileOutputStream,
        startBytes: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var read = startBytes
        var sinceReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            sinceReport += n
            if (sinceReport >= 256 * 1024) {
                sinceReport = 0
                onProgress(read, total)
            }
        }
        out.flush()
        onProgress(read, total)
    }

    // ---------- TTS 文件状态 ----------

    fun ttsDir(context: Context): File = File(File(context.filesDir, "tts"), "kokoro-v1.1-zh")

    /** 旧版 vits-melo 朗读模型目录：已被 kokoro 替代，启动时清掉给用户腾空间 */
    fun cleanupLegacyTts(context: Context) {
        File(context.filesDir, "tts/melo-zh-en").deleteRecursively()
    }

    /** 全部文件都已下载且尺寸达标才算就绪 */
    fun isTtsReady(context: Context): Boolean {
        val dir = ttsDir(context)
        return TTS_FILES.all { File(dir, it.relPath).length() >= it.bytes }
    }

    /** 已下载字节（含未完成的 .part），用于整体进度与续传提示 */
    fun ttsDownloadedBytes(context: Context): Long {
        val dir = ttsDir(context)
        return TTS_FILES.sumOf { f ->
            val done = File(dir, f.relPath)
            if (done.exists()) {
                done.length()
            } else {
                File(dir, "${f.relPath}.part").takeIf { it.exists() }?.length() ?: 0L
            }
        }
    }

    /**
     * 顺序下载全部 TTS 文件（自动跳过已完成的），单文件断点续传。
     * onFileStart(第几个, 共几个, 文件相对路径)；onProgress(整体已下载, 整体总量)。
     */
    suspend fun downloadTtsModel(
        context: Context,
        onFileStart: (index: Int, count: Int, relPath: String) -> Unit = { _, _, _ -> },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val dir = ttsDir(context)
        var base = 0L
        TTS_FILES.forEachIndexed { i, f ->
            onFileStart(i, TTS_FILES.size, f.relPath)
            if (File(dir, f.relPath).length() >= f.bytes) {
                base += f.bytes
                onProgress(base, TTS_TOTAL_BYTES)
                return@forEachIndexed
            }
            downloadFileTo(dir, f.relPath, "$TTS_REPO/${f.relPath}", f.bytes) { done, _ ->
                onProgress(base + done, TTS_TOTAL_BYTES)
            }
            base += f.bytes
        }
    }

    fun deleteTtsModel(context: Context) {
        ttsDir(context).deleteRecursively()
    }

    // ---------- ASR 汇总 ----------

    fun deleteModel(context: Context) {
        val dir = SherpaTranscriber.modelDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(context: Context): Long =
        SherpaTranscriber.modelDir(context).listFiles()?.sumOf { it.length() } ?: 0
}
