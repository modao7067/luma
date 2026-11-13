#include <jni.h>
#include <android/bitmap.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <limits.h>

#include <ghostty/vt.h>
#include <ghostty/vt/key/encoder.h>
#include <ghostty/vt/key/event.h>

static int frame_directory_fd=-1;
static char frame_directory[PATH_MAX];

JNIEXPORT jboolean JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_setFrameDirectory(JNIEnv *env,jclass cls,jstring path) {
    (void)cls; if(!path)return JNI_FALSE;
    const char *name=(*env)->GetStringUTFChars(env,path,NULL); if(!name)return JNI_FALSE;
    bool ok=false; size_t len=strlen(name);
    if(frame_directory_fd>=0)ok=!strcmp(name,frame_directory);
    else if(len>0&&len<sizeof(frame_directory)&&name[0]=='/') {
        int fd=open(name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        struct stat info;
        if(fd>=0&&fstat(fd,&info)==0&&S_ISDIR(info.st_mode)&&info.st_uid==getuid()&&fchmod(fd,0700)==0) {
            memcpy(frame_directory,name,len+1);frame_directory_fd=fd;ok=true;
        } else if(fd>=0)close(fd);
    }
    (*env)->ReleaseStringUTFChars(env,path,name);return ok?JNI_TRUE:JNI_FALSE;
}

int luma_open_frame_file(const uint8_t *path,size_t len) {
    if(frame_directory_fd<0)return -1;
    size_t prefix=strlen(frame_directory);
    if(len<=prefix+1||len>=PATH_MAX||memcmp(path,frame_directory,prefix)||path[prefix]!='/')return -1;
    const uint8_t *leaf=path+prefix+1;size_t size=len-prefix-1;
    const char marker[]="tty-graphics-protocol-px-";
    if(size<=sizeof(marker)-1+5||memcmp(leaf,marker,sizeof(marker)-1)||memcmp(leaf+size-5,".rgba",5))return -1;
    for(size_t i=sizeof(marker)-1;i<size-5;i++)if((leaf[i]<'0'||leaf[i]>'9')&&leaf[i]!='-')return -1;
    char name[NAME_MAX+1];if(size>NAME_MAX)return -1;memcpy(name,leaf,size);name[size]=0;
    int fd=openat(frame_directory_fd,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);
    if(fd<0)return -1;
    struct stat info;
    if(fstat(fd,&info)||!S_ISREG(info.st_mode)||info.st_uid!=getuid()||info.st_nlink!=1||info.st_size<1||info.st_size>16*1024*1024) {close(fd);return -1;}
    return fd;
}

#define SNAP_VERSION 1
#define SNAP_HDR 12
#define SNAP_STRIDE 6
#define ATTR_HAS_TEXT (1 << 0)
#define ATTR_WIDE (1 << 1)
#define ATTR_WIDE_TAIL (1 << 2)
#define ATTR_WIDE_HEAD (1 << 3)
#define ATTR_BOLD (1 << 4)
#define ATTR_ITALIC (1 << 5)
#define ATTR_FAINT (1 << 6)
#define ATTR_BLINK (1 << 7)
#define ATTR_INVERSE (1 << 8)
#define ATTR_INVISIBLE (1 << 9)
#define ATTR_STRIKETHROUGH (1 << 10)
#define ATTR_OVERLINE (1 << 11)
#define ATTR_UNDERLINE_SHIFT 12
#define ATTR_UNDERLINE_MASK 7
#define ATTR_HYPERLINK (1 << 15)
#define ATTR_ROW_WRAP (1 << 16)
#define ATTR_ROW_CLEAN (1 << 17)
#define FLAG_CURSOR_VISIBLE (1 << 0)
#define FLAG_CURSOR_BLINKING (1 << 1)
#define FLAG_CURSOR_IN_VIEWPORT (1 << 2)
#define FLAG_SYNC_OUTPUT (1 << 3)
#define FLAG_ALTERNATE_SCREEN (1 << 4)
#define FLAG_HAS_IMAGES (1 << 6)

#define FEED_HAS_REPLIES (1 << 0)
#define FEED_REPLY_OVERFLOW (1 << 1)
#define FEED_HAS_EVENTS (1 << 2)
#define FEED_HOLD_ACTIVE (1 << 3)
#define FEED_HOLD_CHANGED (1 << 4)
#define FEED_COLORS_CHANGED (1 << 5)
#define FEED_HAS_IMAGES (1 << 6)
#define FEED_ALT_SCREEN (1 << 7)
#define FEED_REVERSE_VIDEO (1 << 8)
#define FEED_ACTIVITY_CHANGED (1 << 9)
#define FEED_AT_BOTTOM (1 << 10)
#define FEED_MODES_MAYBE_CHANGED (1 << 11)

#define REPLY_CAP (1024u * 1024u)
#define PASTE_MAX_BYTES (256u * 1024u)
#define PASTE_OUT_CAP (PASTE_MAX_BYTES + 16u)
#define GRAPHEME_CAP 4096u
#define MAX_HANDLES 64

typedef struct {
    GhosttyTerminal terminal;
    jobject host;
    jmethodID host_hold;
    jmethodID host_replies;
    JNIEnv *call_env;
    GhosttyRenderState render;
    GhosttyRenderStateRowIterator row_iter;
    GhosttyRenderStateRowCells row_cells;
    uint8_t *replies;
    size_t reply_len;
    bool reply_overflow;
    uint32_t *graphemes;
    size_t graphemes_cap;
    int *cluster_bulk;
    size_t cluster_bulk_cap;
    size_t cluster_bulk_len;
    bool cluster_bulk_ready;
    int32_t *snap_buf;
    size_t snap_cap;
    bool force_full_snapshot;
    int32_t cached_palette[259];
    uint32_t palette_gen;
    bool palette_valid;
    int cols, rows;
    uint32_t cell_width, cell_height;
    bool cell_size_valid;
    int hold_active, hold_events;
    bool hold_ignored;
    uint64_t hold_deadline_ms;
    GhosttyTerminalScrollbar hold_bar;
    int metadata_events[14];
    size_t metadata_count;
    uint8_t *clipboard;
    size_t clipboard_len;
    uint8_t *iterm_data;
    size_t iterm_len, iterm_capacity;
    char iterm_args[2048];
    bool iterm_invalid;
    uint64_t last_compressed_pages;
    bool maybe_restored_cold_page;
    uint8_t *paste_src;
    size_t paste_src_len;
    uint8_t *paste_out;
    size_t paste_out_len, paste_out_cap;
    bool paste_active;
    bool paste_failed;
    GhosttyKeyEncoder key_encoder;
    GhosttyKeyEvent key_event;
} LumaVT;

typedef struct {
    uint64_t id;
    LumaVT *vt;
    pthread_t owner;
} HandleSlot;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static HandleSlot g_handles[MAX_HANDLES];
static uint64_t g_next_id = 1;

static LumaVT *registry_get(jlong handle) {
    if (handle <= 0) return NULL;
    LumaVT *found = NULL;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_HANDLES; i++) {
        if (g_handles[i].id == (uint64_t)handle && g_handles[i].vt &&
            pthread_equal(g_handles[i].owner, pthread_self())) {
            found = g_handles[i].vt;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static uint64_t registry_add(LumaVT *vt) {
    uint64_t id = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_HANDLES; i++) if (!g_handles[i].vt) {
        id = g_next_id++;
        g_handles[i].id = id;
        g_handles[i].vt = vt;
        g_handles[i].owner = pthread_self();
        break;
    }
    pthread_mutex_unlock(&g_lock);
    return id;
}

static void registry_remove(LumaVT *vt) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_HANDLES; i++) if (g_handles[i].vt == vt) {
        g_handles[i].vt = NULL; g_handles[i].id = 0;
    }
    pthread_mutex_unlock(&g_lock);
}

static bool drain_replies_to_host(LumaVT *vt) {
    JNIEnv *env = vt->call_env;
    if (!vt->host || !env || !vt->host_replies || (*env)->ExceptionCheck(env)) return false;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)vt->reply_len);
    if (!bytes) return false;
    if (vt->reply_len)
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)vt->reply_len, (const jbyte *)vt->replies);
    (*env)->CallVoidMethod(env, vt->host, vt->host_replies, bytes);
    (*env)->DeleteLocalRef(env, bytes);
    if ((*env)->ExceptionCheck(env)) return false;
    vt->reply_len = 0;
    return true;
}
static bool append_reply(LumaVT *vt, const uint8_t *data, size_t len) {
    while (len > 0) {
        if (vt->reply_len == REPLY_CAP && !drain_replies_to_host(vt)) {
            vt->reply_overflow = true; return false;
        }
        size_t count = len < REPLY_CAP - vt->reply_len ? len : REPLY_CAP - vt->reply_len;
        uint8_t *grown = (uint8_t *)realloc(vt->replies, vt->reply_len + count);
        if (!grown) { vt->reply_overflow = true; return false; }
        vt->replies = grown;
        memcpy(vt->replies + vt->reply_len, data, count);
        vt->reply_len += count; data += count; len -= count;
    }
    return true;
}

static bool append_paste(LumaVT *vt, const uint8_t *data, size_t len) {
    if (len == 0) return true;
    if (len > PASTE_OUT_CAP - vt->paste_out_len) { vt->paste_failed = true; return false; }
    size_t need = vt->paste_out_len + len;
    if (need > vt->paste_out_cap) {
        size_t cap = vt->paste_out_cap ? vt->paste_out_cap : 4096;
        while (cap < need) cap *= 2;
        if (cap > PASTE_OUT_CAP) cap = PASTE_OUT_CAP;
        uint8_t *grown = (uint8_t *)realloc(vt->paste_out, cap);
        if (!grown) { vt->paste_failed = true; return false; }
        vt->paste_out = grown;
        vt->paste_out_cap = cap;
    }
    memcpy(vt->paste_out + vt->paste_out_len, data, len);
    vt->paste_out_len = need;
    return true;
}

static void on_write_pty(GhosttyTerminal terminal, void *userdata, const uint8_t *data, size_t len) {
    (void)terminal;
    LumaVT *vt = (LumaVT *)userdata;
    if (vt->paste_active) { append_paste(vt, data, len); return; }
    append_reply(vt, data, len);
}

extern int luma_ghostty_color_scheme(GhosttyTerminal terminal);

static bool on_size_report(GhosttyTerminal terminal, void *userdata, GhosttySizeReportSize *out_size) {
    (void)terminal;
    LumaVT *vt = (LumaVT *)userdata;
    if (!vt || !vt->cell_size_valid || !out_size || vt->cols < 1 || vt->rows < 1) return false;
    out_size->rows = (uint16_t)vt->rows;
    out_size->columns = (uint16_t)vt->cols;
    out_size->cell_width = vt->cell_width;
    out_size->cell_height = vt->cell_height;
    return true;
}

static bool on_color_scheme(GhosttyTerminal terminal, void *userdata, GhosttyColorScheme *out_scheme) {
    (void)userdata;
    int scheme = luma_ghostty_color_scheme(terminal);
    if (scheme < 0 || !out_scheme) return false;
    *out_scheme = scheme == 1 ? GHOSTTY_COLOR_SCHEME_DARK : GHOSTTY_COLOR_SCHEME_LIGHT;
    return true;
}

static bool paste_reader(void *userdata, GhosttyString mime, GhosttyWriter writer) {
    (void)mime;
    LumaVT *vt = (LumaVT *)userdata;
    if (!vt || !writer.write) return false;
    if (vt->paste_src_len == 0) return true;
    return writer.write(writer.userdata, vt->paste_src, vt->paste_src_len);
}

static uint64_t monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000u + (uint64_t)now.tv_nsec / 1000000u;
}

static void queue_event(LumaVT *vt, int kind, int value) {
    if (kind < 1 || kind > 6) return;
    for (size_t i = 0; i < vt->metadata_count;) {
        if (vt->metadata_events[i] != kind ||
            (kind == 4 && value >= 0 && vt->metadata_events[i + 1] < 0)) { i += 2; continue; }
        memmove(vt->metadata_events + i, vt->metadata_events + i + 2,
            (vt->metadata_count - i - 2) * sizeof(vt->metadata_events[0]));
        vt->metadata_count -= 2;
    }
    vt->metadata_events[vt->metadata_count++] = kind;
    vt->metadata_events[vt->metadata_count++] = value;
}
static void on_title(GhosttyTerminal terminal, void *userdata) {
    (void)terminal; queue_event(userdata, 1, 0);
}
static void on_pwd(GhosttyTerminal terminal, void *userdata) {
    (void)terminal; queue_event(userdata, 2, 0);
}
static void on_bell(GhosttyTerminal terminal, void *userdata) {
    (void)terminal; queue_event(userdata, 3, 0);
}
static void on_semantic(GhosttyTerminal terminal, void *userdata, const GhosttyTerminalSemanticPrompt *event) {
    (void)terminal;
    if (event->kind == GHOSTTY_SEMANTIC_PROMPT_OUTPUT_START) queue_event(userdata, 4, -1);
    else if (event->kind == GHOSTTY_SEMANTIC_PROMPT_COMMAND_END)
        queue_event(userdata, 4, event->has_exit_code && event->exit_code != 0 ? 1 : 0);
}
static void on_inline_image(GhosttyTerminal terminal, void *userdata, const GhosttyTerminalUnknownSequence *sequence);
extern void luma_ghostty_inline_enable(GhosttyTerminal terminal);
extern int luma_ghostty_state_flags(GhosttyTerminal terminal, int32_t *dyn_out);
extern int luma_ghostty_read_colors(GhosttyTerminal terminal, int32_t *out, size_t len);
static void on_reset(GhosttyTerminal terminal, void *userdata) {
    (void)terminal; LumaVT *vt=userdata;
    free(vt->iterm_data); vt->iterm_data=NULL; vt->iterm_len=vt->iterm_capacity=0;
    vt->iterm_args[0]=0; vt->iterm_invalid=false; vt->last_compressed_pages=0;
    vt->maybe_restored_cold_page=false; vt->force_full_snapshot=true; vt->palette_valid=false;
    queue_event(userdata, 5, 0);
}
static void on_clipboard(GhosttyTerminal terminal, void *userdata, const GhosttyClipboardWrite *write) {
    (void)terminal;
    LumaVT *vt = userdata;
    GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
    for (size_t i = 0; i < write->contents_len; i++) {
        const GhosttyClipboardContent *content = &write->contents[i];
        if (content->mime.len < 10 || memcmp(content->mime.ptr, "text/plain", 10) != 0) continue;
        if (content->data.len > REPLY_CAP) { reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA; break; }
        uint8_t *copy = malloc(content->data.len ? content->data.len : 1);
        if (!copy) { reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR; break; }
        if (content->data.len) memcpy(copy, content->data.ptr, content->data.len);
        free(vt->clipboard); vt->clipboard = copy; vt->clipboard_len = content->data.len;
        queue_event(vt, 6, 0);
        reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
        break;
    }
    write->reply(write, &reply);
}
static void on_render_hold(GhosttyTerminal terminal, void *userdata, bool held) {
    LumaVT *vt = userdata;
    if (!vt || !vt->render) return;
    vt->hold_events++;
    vt->hold_ignored = false;
    vt->hold_active = held ? 1 : 0;
    vt->hold_deadline_ms = held ? monotonic_ms() + 1000 : 0;
    if (held) {
        ghostty_render_state_update(vt->render, terminal);
        ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &vt->hold_bar);
    }    if (vt->host && vt->call_env)
        (*vt->call_env)->CallVoidMethod(vt->call_env, vt->host, vt->host_hold, held ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_destroyChecked(JNIEnv*,jclass,jlong);

static bool make_point(LumaVT *vt, int y, int x, GhosttyPoint *out) {
    if (y < 0 || y >= vt->rows || x < 0 || x >= vt->cols) return false;
    out->tag = GHOSTTY_POINT_TAG_VIEWPORT;
    out->value.coordinate.x = (uint16_t)x;
    out->value.coordinate.y = (uint32_t)y;
    return true;
}

static bool ensure_graphemes(LumaVT *vt, size_t count) {
    if (count <= vt->graphemes_cap) return true;
    if (count > GRAPHEME_CAP) return false;
    size_t cap = vt->graphemes_cap ? vt->graphemes_cap : 8;
    while (cap < count) cap *= 2;
    if (cap > GRAPHEME_CAP) cap = GRAPHEME_CAP;
    uint32_t *grown = (uint32_t *)realloc(vt->graphemes, cap * sizeof(uint32_t));
    if (!grown) return false;
    vt->graphemes = grown;
    vt->graphemes_cap = cap;
    return true;
}

static uint32_t fnv_cluster(const uint32_t *cp, size_t len) {
    uint32_t h = 0x811C9DC5u;
    for (size_t i = 0; i < len; i++) { h ^= cp[i]; h *= 16777619u; }
    return h;
}

static int32_t color_value(GhosttyStyleColor color, const GhosttyRenderStateColors *colors) {
    if (color.tag == GHOSTTY_STYLE_COLOR_RGB)
        return (color.value.rgb.r << 16) | (color.value.rgb.g << 8) | color.value.rgb.b;
    if (color.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
        GhosttyColorRgb c = colors->palette[color.value.palette];
        return (c.r << 16) | (c.g << 8) | c.b;
    }
    return -1;
}

static int mode_value(GhosttyTerminal terminal, int number) {
    GhosttyTerminalModeConfig cfg = {
        .mode = ghostty_mode_new((uint16_t)number, false),
        .value = false,
    };
    GhosttyResult result = ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_MODE, &cfg);
    if (result != GHOSTTY_SUCCESS) return -1;
    return cfg.value ? 1 : 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_ping(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 42;
}

JNIEXPORT jstring JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_buildInfo(JNIEnv *env, jclass cls) {
    (void)cls;
    GhosttyString version = {0};
    ghostty_build_info(GHOSTTY_BUILD_INFO_VERSION_STRING, &version);
    bool kitty = false;
    ghostty_build_info(GHOSTTY_BUILD_INFO_KITTY_GRAPHICS, &kitty);
    char buf[200];
    snprintf(buf, sizeof buf, "libghostty-vt %.*s kitty=%d", (int)version.len, (const char *)version.ptr, kitty);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jlong JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_create(
        JNIEnv *env, jclass cls, jint cols, jint rows, jint scrollbackLines,
        jintArray palette, jint foreground, jint background) {
    (void)cls;
    if (cols < 1 || rows < 1 || cols > 65535 || rows > 65535) return 0;
    if ((uint64_t)cols * (uint64_t)rows > 1048576u) return 0;
    if (!palette || (*env)->GetArrayLength(env, palette) < 256) return 0;
    LumaVT *vt = (LumaVT *)calloc(1, sizeof(LumaVT));
    if (!vt) return 0;
    if (ghostty_terminal_new(NULL, &vt->terminal, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS) {
        free(vt);
        return 0;
    }
    vt->cols = cols;
    vt->rows = rows;
    vt->cell_size_valid = false;
    vt->call_env = env;
    ghostty_terminal_resize(vt->terminal, (uint16_t)cols, (uint16_t)rows, 1, 1);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, vt);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_write_pty);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SIZE, (const void *)on_size_report);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME, (const void *)on_color_scheme);
    GhosttyString terminfo_name = {.ptr = (const uint8_t *)"xterm-256color", .len = 14};
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_TERMINFO_NAME, &terminfo_name);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_render_hold);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void *)on_title);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_PWD_CHANGED, (const void *)on_pwd);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_BELL, (const void *)on_bell);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT, (const void *)on_semantic);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_RESET, (const void *)on_reset);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)on_inline_image);
    size_t unknown_limit=1024;
    ghostty_terminal_set(vt->terminal,GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES,&unknown_limit);
    luma_ghostty_inline_enable(vt->terminal);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)on_clipboard);
    size_t continuation_limit = 1024;
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &continuation_limit);

    size_t bytes = 50u * 1024u * 1024u;
    size_t lines = scrollbackLines < 0 ? 0 : (size_t)(scrollbackLines > 10000 ? 10000 : scrollbackLines);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &bytes);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);

    bool disable_medium = false;
    bool file_medium = frame_directory_fd >= 0;
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE, &file_medium);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM, &disable_medium);
    GhosttyString frame_dir={.ptr=frame_directory,.len=strlen(frame_directory)};
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE, file_medium ? &frame_dir : NULL);
    uint64_t image_limit = 48u * 1024u * 1024u;
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &image_limit);

    jint local[256];
    (*env)->GetIntArrayRegion(env, palette, 0, 256, local);
    GhosttyColorRgb rgb[256];
    for (int i = 0; i < 256; i++) {
        int v = local[i];
        rgb[i].r = (uint8_t)(v >> 16); rgb[i].g = (uint8_t)(v >> 8); rgb[i].b = (uint8_t)v;
    }
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, rgb);
    GhosttyColorRgb fg = {(uint8_t)(foreground >> 16), (uint8_t)(foreground >> 8), (uint8_t)foreground};
    GhosttyColorRgb bg = {(uint8_t)(background >> 16), (uint8_t)(background >> 8), (uint8_t)background};
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
    ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);

    if (ghostty_render_state_new(NULL, &vt->render) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(NULL, &vt->row_iter) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(NULL, &vt->row_cells) != GHOSTTY_SUCCESS) {
        if (vt->render) ghostty_render_state_free(vt->render);
        if (vt->row_iter) ghostty_render_state_row_iterator_free(vt->row_iter);
        if (vt->row_cells) ghostty_render_state_row_cells_free(vt->row_cells);
        ghostty_terminal_free(vt->terminal);
        free(vt);
        return 0;
    }
    vt->force_full_snapshot = true;
    vt->palette_gen = 1;
    uint64_t id = registry_add(vt);
    if (!id) {
        ghostty_render_state_row_iterator_free(vt->row_iter);
        ghostty_render_state_row_cells_free(vt->row_cells);
        ghostty_render_state_free(vt->render);
        ghostty_terminal_free(vt->terminal);
        free(vt);
        return 0;
    }
    return (jlong)id;
}

JNIEXPORT void JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_destroy(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return;
    registry_remove(vt);
    if (vt->host) { (*env)->DeleteGlobalRef(env, vt->host); vt->host=NULL; }
    if (vt->row_iter) ghostty_render_state_row_iterator_free(vt->row_iter);
    if (vt->row_cells) ghostty_render_state_row_cells_free(vt->row_cells);
    if (vt->render) ghostty_render_state_free(vt->render);
    if (vt->key_encoder) ghostty_key_encoder_free(vt->key_encoder);
    if (vt->key_event) ghostty_key_event_free(vt->key_event);
    if (vt->terminal) ghostty_terminal_free(vt->terminal);
    free(vt->replies);
    free(vt->graphemes);
    free(vt->cluster_bulk);
    free(vt->snap_buf);
    free(vt->clipboard);
    free(vt->iterm_data);
    free(vt->paste_out);
    free(vt);
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_feed(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint offset, jint length) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !data || offset < 0 || length < 0) return -1;
    jsize array_len = (*env)->GetArrayLength(env, data);
    if (offset > array_len || length > array_len - offset) return -1;
    if (length == 0) return 0;
    uint8_t *copy = (uint8_t *)malloc((size_t)length);
    if (!copy) return -2;
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *)copy);
    int prev_hold_events = vt->hold_events;
    uint64_t prev_activity = 0, post_activity = 0;
    ghostty_terminal_compression_activity(vt->terminal, &prev_activity);
    int32_t prev_dyn[3] = {0}, post_dyn[3] = {0};
    int prev_flags = luma_ghostty_state_flags(vt->terminal, prev_dyn);
    bool has_esc_or_shift = memchr(copy, 0x1b, (size_t)length) != NULL ||
        memchr(copy, 0x0e, (size_t)length) != NULL || memchr(copy, 0x0f, (size_t)length) != NULL;
    vt->call_env = env;
    ghostty_terminal_vt_write(vt->terminal, copy, (size_t)length);
    vt->call_env = NULL;
    free(copy);
    int post_flags = luma_ghostty_state_flags(vt->terminal, post_dyn);
    ghostty_terminal_compression_activity(vt->terminal, &post_activity);
    jint status = 0;
    if (vt->reply_len > 0) status |= FEED_HAS_REPLIES;
    if (vt->reply_overflow) status |= FEED_REPLY_OVERFLOW;
    if (vt->metadata_count > 0) status |= FEED_HAS_EVENTS;
    if (vt->hold_active) status |= FEED_HOLD_ACTIVE;
    if (vt->hold_events != prev_hold_events) status |= FEED_HOLD_CHANGED;
    if ((post_flags & (1 << 0)) != 0 || memcmp(prev_dyn, post_dyn, sizeof(prev_dyn)) != 0 || vt->metadata_count > 0)
        status |= FEED_COLORS_CHANGED;
    if ((post_flags & (1 << 3)) != 0) status |= FEED_HAS_IMAGES;
    if ((post_flags & (1 << 1)) != 0) status |= FEED_ALT_SCREEN;
    if ((post_flags & (1 << 2)) != 0) status |= FEED_REVERSE_VIDEO;
    if (post_activity != prev_activity || (vt->last_compressed_pages > 0 && vt->maybe_restored_cold_page))
        status |= FEED_ACTIVITY_CHANGED;
    if ((post_flags & (1 << 4)) != 0) status |= FEED_AT_BOTTOM;
    if (has_esc_or_shift || ((prev_flags | post_flags) & (1 << 5)) != 0 ||
            ((prev_flags ^ post_flags) & ((1 << 1) | (1 << 2))) != 0)
        status |= FEED_MODES_MAYBE_CHANGED;
    return status;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_resize(
        JNIEnv *env, jclass cls, jlong handle, jint cols, jint rows, jint cellWidth, jint cellHeight) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || cols < 1 || rows < 1 || cols > 65535 || rows > 65535) return -1;
    if ((uint64_t)cols * (uint64_t)rows > 1048576u) return -1;
    uint32_t cell_w = cellWidth > 0 ? (uint32_t)cellWidth : 0;
    uint32_t cell_h = cellHeight > 0 ? (uint32_t)cellHeight : 0;
    ghostty_terminal_resize(vt->terminal, (uint16_t)cols, (uint16_t)rows,
                            cell_w ? cell_w : 1, cell_h ? cell_h : 1);
    vt->cell_width = cell_w;
    vt->cell_height = cell_h;
    vt->cell_size_valid = cell_w > 0 && cell_h > 0;
    vt->call_env = NULL;
    vt->cols = cols;
    vt->rows = rows;
    vt->force_full_snapshot = true;
    vt->cluster_bulk_ready = false;
    return 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_paste(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray text, jboolean allowUnsafe) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !text || vt->paste_active) return -1;
    jsize len = (*env)->GetArrayLength(env, text);
    if (len <= 0) return 2;
    if ((size_t)len > PASTE_MAX_BYTES) return -2;
    uint8_t *src = (uint8_t *)malloc((size_t)len);
    if (!src) return -1;
    (*env)->GetByteArrayRegion(env, text, 0, len, (jbyte *)src);
    vt->paste_src = src;
    vt->paste_src_len = (size_t)len;
    vt->paste_out_len = 0;
    vt->paste_failed = false;
    vt->paste_active = true;
    GhosttyPaste paste = GHOSTTY_INIT_SIZED(GhosttyPaste);
    paste.location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD;
    paste.source = GHOSTTY_PASTE_SOURCE_CLIPBOARD;
    GhosttyString mime = {.ptr = (const uint8_t *)"text/plain", .len = 10};
    paste.mimes = &mime;
    paste.mimes_len = 1;
    paste.reader.read = paste_reader;
    paste.reader.userdata = vt;
    paste.allow_unsafe = allowUnsafe == JNI_TRUE;
    bool written = false;
    GhosttyResult result = ghostty_terminal_paste(vt->terminal, &paste, &written);
    vt->paste_active = false;
    vt->paste_src = NULL;
    vt->paste_src_len = 0;
    free(src);
    if (result == GHOSTTY_REJECTED) { vt->paste_out_len = 0; return 0; }
    if (result != GHOSTTY_SUCCESS) { vt->paste_out_len = 0; return -1; }
    if (vt->paste_failed) { vt->paste_out_len = 0; return -1; }
    if (!written || vt->paste_out_len == 0) { vt->paste_out_len = 0; return 2; }
    if (vt->paste_out_len > REPLY_CAP - vt->reply_len) {
        vt->paste_out_len = 0;
        return -2;
    }
    bool queued = append_reply(vt, vt->paste_out, vt->paste_out_len);
    vt->paste_out_len = 0;
    return queued ? 1 : -2;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_queryMode(
        JNIEnv *env, jclass cls, jlong handle, jint number) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || number < 0 || number > 65535) return -1;
    return mode_value(vt->terminal, number);
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_snapshot(
        JNIEnv *env, jclass cls, jlong handle, jintArray out, jint outLen) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return -1;
    if ((uint64_t)vt->cols * (uint64_t)vt->rows > 1048576u) return -6;
    uint64_t cells = (uint64_t)vt->cols * (uint64_t)vt->rows;
    if (cells > (UINT32_MAX - SNAP_HDR) / SNAP_STRIDE) return -6;
    int need = SNAP_HDR + (int)(cells * SNAP_STRIDE);
    if (!out) return need;
    jsize actual = (*env)->GetArrayLength(env, out);
    if (outLen < 0 || outLen > actual) return -1;
    if (outLen < need) return need;

    GhosttyRenderState state = vt->render;
    if (!vt->hold_active && ghostty_render_state_update(vt->render, vt->terminal) != GHOSTTY_SUCCESS) {
        return -2;
    }
    jint caller_req = 0;
    (*env)->GetIntArrayRegion(env, out, 0, 1, &caller_req);
    bool force_full = vt->force_full_snapshot || (caller_req == -1);
    GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);
    ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    int32_t cur_pal[259];
    for (int i = 0; i < 256; i++) cur_pal[i] = (colors.palette[i].r << 16) | (colors.palette[i].g << 8) | colors.palette[i].b;
    cur_pal[256] = (colors.foreground.r << 16) | (colors.foreground.g << 8) | colors.foreground.b;
    cur_pal[257] = (colors.background.r << 16) | (colors.background.g << 8) | colors.background.b;
    cur_pal[258] = colors.cursor_has_value
        ? (colors.cursor.r << 16) | (colors.cursor.g << 8) | colors.cursor.b : cur_pal[256];
    if (!vt->palette_valid || memcmp(cur_pal, vt->cached_palette, sizeof(cur_pal)) != 0) {
        memcpy(vt->cached_palette, cur_pal, sizeof(cur_pal));
        vt->palette_valid = true;
        vt->palette_gen++;
        force_full = true;
    }
    int st_flags = luma_ghostty_state_flags(vt->terminal, NULL);
    GhosttyTerminalScreen screen = (st_flags & (1 << 1)) != 0 ? GHOSTTY_TERMINAL_SCREEN_ALTERNATE : GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    GhosttyTerminalScrollbar bar = {0};
    ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    size_t history = bar.total > bar.len ? bar.total - bar.len : 0;
    if (vt->hold_active) { bar = vt->hold_bar; history = bar.total > bar.len ? bar.total - bar.len : 0; }
    int32_t origin = (int32_t)bar.offset - (int32_t)history;
    if (origin < 0) vt->maybe_restored_cold_page = true;

    if ((size_t)need > vt->snap_cap) {
        int32_t *grown = (int32_t *)realloc(vt->snap_buf, (size_t)need * sizeof(int32_t));
        if (!grown) return -3;
        vt->snap_buf = grown;
        vt->snap_cap = (size_t)need;
    }
    int32_t *snap = vt->snap_buf;
    int flags = 0;
    if (cursor.visible) flags |= FLAG_CURSOR_VISIBLE;
    if (cursor.blinking) flags |= FLAG_CURSOR_BLINKING;
    if (cursor.viewport_has_value) flags |= FLAG_CURSOR_IN_VIEWPORT;
    if (mode_value(vt->terminal, 2026) == 1) flags |= FLAG_SYNC_OUTPUT;
    if ((st_flags & (1 << 2)) != 0) flags |= (1 << 5);
    if (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) flags |= FLAG_ALTERNATE_SCREEN;
    if ((st_flags & (1 << 3)) != 0) flags |= FLAG_HAS_IMAGES;
    int cursor_style = 0;
    if (cursor.visual_style == GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR) cursor_style = 2;
    else if (cursor.visual_style == GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE) cursor_style = 1;
    snap[0] = SNAP_VERSION; snap[1] = vt->cols; snap[2] = vt->rows;
    snap[3] = cursor.viewport_has_value ? cursor.viewport_x : -1;
    snap[4] = cursor.viewport_has_value ? cursor.viewport_y : -1;
    snap[5] = flags; snap[6] = cursor_style;
    snap[7] = (int32_t)vt->palette_gen;
    snap[8] = (int32_t)history; snap[9] = 0; snap[10] = origin; snap[11] = (int32_t)bar.total;

    if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &vt->row_iter) != GHOSTTY_SUCCESS) {
        return -4;
    }
    size_t cluster_offset = 0;
    while (ghostty_render_state_row_iterator_next(vt->row_iter)) {
        int32_t viewport_y = -1;
        ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &viewport_y);
        if (viewport_y < 0 || viewport_y >= vt->rows) continue;
        bool row_dirty = true;
        ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &row_dirty);
        GhosttyRow raw_row = 0;
        bool wrap = false;
        if (ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &raw_row) == GHOSTTY_SUCCESS)
            ghostty_row_get(raw_row, GHOSTTY_ROW_DATA_WRAP, &wrap);
        int32_t *row_out = snap + SNAP_HDR + ((size_t)viewport_y * (size_t)vt->cols) * SNAP_STRIDE;
        if (!force_full && !row_dirty) {
            row_out[1] = ATTR_ROW_CLEAN | (wrap ? ATTR_ROW_WRAP : 0);
            continue;
        }
        memset(row_out, 0, (size_t)vt->cols * SNAP_STRIDE * sizeof(int32_t));
        if (ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &vt->row_cells) != GHOSTTY_SUCCESS) {
            if (wrap) row_out[1] |= ATTR_ROW_WRAP;
            continue;
        }
        uint16_t column = 0;
        while (ghostty_render_state_row_cells_next(vt->row_cells)) {
            if (column >= vt->cols) break;
            uint32_t cluster_len = 0;
            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
            GhosttyCell raw = 0;
            const GhosttyRenderStateRowCellsData keys[] = {
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
            };
            void *values[] = {&cluster_len, &style, &raw};
            size_t written = 0;
            if (ghostty_render_state_row_cells_get_multi(vt->row_cells, 3, keys, values, &written) != GHOSTTY_SUCCESS ||
                written != 3) { column++; continue; }

            bool has_text = false, has_hyperlink = false;
            GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
            uint32_t cp = 0;
            const GhosttyCellData cellkeys[] = {
                GHOSTTY_CELL_DATA_HAS_TEXT, GHOSTTY_CELL_DATA_HAS_HYPERLINK, GHOSTTY_CELL_DATA_WIDE, GHOSTTY_CELL_DATA_CODEPOINT,
            };
            void *cellvalues[] = {&has_text, &has_hyperlink, &wide, &cp};
            size_t cellwritten = 0;
            ghostty_cell_get_multi(raw, 4, cellkeys, cellvalues, &cellwritten);

            uint32_t base = cp;
            size_t cluster = 0;
            if (cluster_len > 1) {
                if (!ensure_graphemes(vt, cluster_len)) return -5;
                if (ghostty_render_state_row_cells_get(vt->row_cells,
                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, vt->graphemes) != GHOSTTY_SUCCESS) {
                    return -5;
                }
                if (cluster_offset + cluster_len > vt->cluster_bulk_cap) {
                    size_t new_cap = vt->cluster_bulk_cap ? vt->cluster_bulk_cap * 2 : 64;
                    while (new_cap < cluster_offset + cluster_len) new_cap *= 2;
                    int *grown = (int *)realloc(vt->cluster_bulk, new_cap * sizeof(int));
                    if (!grown) return -5;
                    vt->cluster_bulk = grown;
                    vt->cluster_bulk_cap = new_cap;
                }
                memcpy(vt->cluster_bulk + cluster_offset, vt->graphemes, cluster_len * sizeof(int));
                base = vt->graphemes[0];
                cluster = cluster_len;
            }

            int32_t *cell = row_out + (size_t)column * SNAP_STRIDE;
            cell[0] = (int32_t)base;
            int attr = 0;
            if (has_text) attr |= ATTR_HAS_TEXT;
            if (wide == GHOSTTY_CELL_WIDE_WIDE) attr |= ATTR_WIDE;
            else if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) attr |= ATTR_WIDE_TAIL;
            else if (wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) attr |= ATTR_WIDE_HEAD;
            if (style.bold) attr |= ATTR_BOLD;
            if (style.italic) attr |= ATTR_ITALIC;
            if (style.faint) attr |= ATTR_FAINT;
            if (style.blink) attr |= ATTR_BLINK;
            if (style.inverse) attr |= ATTR_INVERSE;
            if (style.invisible) attr |= ATTR_INVISIBLE;
            if (style.strikethrough) attr |= ATTR_STRIKETHROUGH;
            if (style.overline) attr |= ATTR_OVERLINE;
            if (style.underline > 0) attr |= (style.underline & ATTR_UNDERLINE_MASK) << ATTR_UNDERLINE_SHIFT;
            if (has_hyperlink) attr |= ATTR_HYPERLINK;
            if (column == 0 && wrap) attr |= ATTR_ROW_WRAP;
            cell[1] = attr;
            cell[2] = color_value(style.fg_color, &colors);
            GhosttyColorRgb background;
            cell[3] = ghostty_render_state_row_cells_get(vt->row_cells,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &background) == GHOSTTY_SUCCESS
                ? (background.r << 16) | (background.g << 8) | background.b : -1;
            cell[4] = (int32_t)cluster;
            cell[5] = (int32_t)cluster_offset;
            cluster_offset += cluster;
            column++;
        }
        if (wrap) row_out[1] |= ATTR_ROW_WRAP;
    }
    vt->cluster_bulk_len = cluster_offset;
    vt->cluster_bulk_ready = true;
    ghostty_render_state_clean(state);
    vt->force_full_snapshot = false;
    snap[9] = (int32_t)cluster_offset;
    (*env)->SetIntArrayRegion(env, out, 0, need, (const jint *)snap);
    return need;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_graphemeCluster(
        JNIEnv *env, jclass cls, jlong handle, jint row, jint column, jintArray out) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !out || row < 0 || row >= vt->rows || column < 0 || column >= vt->cols) return -1;
    vt->maybe_restored_cold_page = true;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint point = {
        .tag = GHOSTTY_POINT_TAG_VIEWPORT,
        .value = {.coordinate = {.x = (uint16_t)column, .y = (uint16_t)row}},
    };
    if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) return -2;
    size_t len = 0;
    if (ghostty_grid_ref_graphemes(&ref, NULL, 0, &len) != GHOSTTY_OUT_OF_SPACE) {
        return len == 0 ? 0 : -3;
    }
    jsize cap = (*env)->GetArrayLength(env, out);
    if (len > (size_t)cap) return -(jint)len;
    if (!ensure_graphemes(vt, len)) return -4;
    if (ghostty_grid_ref_graphemes(&ref, vt->graphemes, len, &len) != GHOSTTY_SUCCESS) return -5;
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)len, (const jint *)vt->graphemes);
    return (jint)len;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_clusters(
        JNIEnv *env, jclass cls, jlong handle, jintArray out) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !out) return -1;
    if (vt->cluster_bulk_ready) {
        size_t total = vt->cluster_bulk_len;
        if (total > (size_t)(*env)->GetArrayLength(env, out)) return (jint)total;
        if (total) (*env)->SetIntArrayRegion(env, out, 0, (jsize)total, (const jint *)vt->cluster_bulk);
        return (jint)total;
    }
    GhosttyRenderState state = vt->render;
    if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &vt->row_iter) != GHOSTTY_SUCCESS)
        return -2;
    size_t total = 0;
    while (ghostty_render_state_row_iterator_next(vt->row_iter)) {
        int32_t viewport_y = -1;
        ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &viewport_y);
        if (viewport_y < 0 || viewport_y >= vt->rows) continue;
        if (ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &vt->row_cells) != GHOSTTY_SUCCESS) continue;
        uint16_t column = 0;
        while (column < vt->cols && ghostty_render_state_row_cells_next(vt->row_cells)) {
            uint32_t len = 0;
            const GhosttyRenderStateRowCellsData key = GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN;
            void *value = &len;
            size_t written = 0;
            ghostty_render_state_row_cells_get_multi(vt->row_cells, 1, &key, &value, &written);
            total += len;
            column++;
        }
    }
    if (total > (size_t)(*env)->GetArrayLength(env, out)) return (jint)total;
    if (total > vt->cluster_bulk_cap) {
        int *grown = (int *)realloc(vt->cluster_bulk, total * sizeof(int));
        if (!grown) return -3;
        vt->cluster_bulk = grown;
        vt->cluster_bulk_cap = total;
    }
    if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &vt->row_iter) != GHOSTTY_SUCCESS)
        return -2;
    size_t at = 0;
    while (ghostty_render_state_row_iterator_next(vt->row_iter)) {
        int32_t viewport_y = -1;
        ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &viewport_y);
        if (viewport_y < 0 || viewport_y >= vt->rows) continue;
        if (ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &vt->row_cells) != GHOSTTY_SUCCESS) continue;
        uint16_t column = 0;
        while (column < vt->cols && ghostty_render_state_row_cells_next(vt->row_cells)) {
            uint32_t len = 0;
            const GhosttyRenderStateRowCellsData key = GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN;
            void *value = &len;
            size_t written = 0;
            ghostty_render_state_row_cells_get_multi(vt->row_cells, 1, &key, &value, &written);
            if (len > 0) {
                if (!ensure_graphemes(vt, len)) return -3;
                if (ghostty_render_state_row_cells_get(vt->row_cells,
                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, vt->graphemes) != GHOSTTY_SUCCESS)
                    return -3;
                memcpy(vt->cluster_bulk + at, vt->graphemes, len * sizeof(int));
                at += len;
            }
            column++;
        }
    }
    if (at) (*env)->SetIntArrayRegion(env, out, 0, (jsize)at, (const jint *)vt->cluster_bulk);
    return (jint)at;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_palette(
        JNIEnv *env, jclass cls, jlong handle, jintArray out) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !out) return -1;
    if ((*env)->GetArrayLength(env, out) < 259) return 259;
    int32_t values[259];
    if (vt->hold_active) {
        GhosttyRenderState state = vt->render;
        GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
        if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors) != GHOSTTY_SUCCESS) return -2;
        for (int i = 0; i < 256; i++) values[i] = (colors.palette[i].r << 16) | (colors.palette[i].g << 8) | colors.palette[i].b;
        values[256] = (colors.foreground.r << 16) | (colors.foreground.g << 8) | colors.foreground.b;
        values[257] = (colors.background.r << 16) | (colors.background.g << 8) | colors.background.b;
        values[258] = colors.cursor_has_value
            ? (colors.cursor.r << 16) | (colors.cursor.g << 8) | colors.cursor.b : values[256];
    } else if (luma_ghostty_read_colors(vt->terminal, values, 259) != 259) {
        return -2;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 259, (const jint *)values);
    return 259;
}

JNIEXPORT jlongArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_scrollbar(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    GhosttyTerminalScrollbar bar = {0};
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar) != GHOSTTY_SUCCESS) return NULL;
    jlong values[4] = {(jlong)bar.total, (jlong)bar.offset, (jlong)bar.len,
        (jlong)bar.total - (jlong)bar.len};
    jlongArray out = (*env)->NewLongArray(env, 4);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 4, values);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_scrollViewport(
        JNIEnv *env, jclass cls, jlong handle, jint tag, jlong value) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return -1;
    if (tag == GHOSTTY_SCROLL_VIEWPORT_BOTTOM && (luma_ghostty_state_flags(vt->terminal, NULL) & (1 << 4)) != 0)
        return 0;
    GhosttyTerminalScrollViewport scroll = {0};
    scroll.tag = (GhosttyTerminalScrollViewportTag)tag;
    if (tag == GHOSTTY_SCROLL_VIEWPORT_DELTA) scroll.value.delta = (intptr_t)value;
    else if (tag == GHOSTTY_SCROLL_VIEWPORT_ROW) scroll.value.row = (size_t)value;
    ghostty_terminal_scroll_viewport(vt->terminal, scroll);
    vt->cluster_bulk_ready = false;
    return 0;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_copyTextBytes(
        JNIEnv *env, jclass cls, jlong handle, jint r1, jint c1, jint r2, jint c2,
        jboolean trim, jboolean unwrap) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    if (c1 < 0 || c1 >= vt->cols || c2 < 0 || c2 >= vt->cols || r1 < 0 || r2 < 0) return NULL;
    vt->maybe_restored_cold_page = true;
    GhosttyPoint pa = {0}, pb = {0};
    pa.tag = GHOSTTY_POINT_TAG_SCREEN; pb.tag = GHOSTTY_POINT_TAG_SCREEN;
    pa.value.coordinate.x = (uint16_t)c1; pa.value.coordinate.y = (uint32_t)r1;
    pb.value.coordinate.x = (uint16_t)c2; pb.value.coordinate.y = (uint32_t)r2;
    GhosttyGridRef a = GHOSTTY_INIT_SIZED(GhosttyGridRef), b = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(vt->terminal, pa, &a) != GHOSTTY_SUCCESS ||
        ghostty_terminal_grid_ref(vt->terminal, pb, &b) != GHOSTTY_SUCCESS) return NULL;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    sel.start = a; sel.end = b; sel.rectangle = false;
    GhosttyFormatterTerminalOptions opt = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
    opt.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN; opt.trim = trim; opt.unwrap = unwrap; opt.selection = &sel;
    GhosttyFormatter fmt = NULL;
    if (ghostty_formatter_terminal_new(NULL, &fmt, vt->terminal, opt) != GHOSTTY_SUCCESS) return NULL;
    size_t need = 0;
    GhosttyResult rc = ghostty_formatter_format_buf(fmt, NULL, 0, &need);
    if ((rc != GHOSTTY_OUT_OF_SPACE && rc != GHOSTTY_SUCCESS) || need > 8u * 1024u * 1024u) {
        ghostty_formatter_free(fmt); return NULL;
    }
    uint8_t *buf = (uint8_t *)malloc(need ? need : 1);
    if (!buf) { ghostty_formatter_free(fmt); return NULL; }
    size_t written = 0;
    rc = ghostty_formatter_format_buf(fmt, buf, need, &written);
    ghostty_formatter_free(fmt);
    jbyteArray out = NULL;
    if (rc == GHOSTTY_SUCCESS) {
        out = (*env)->NewByteArray(env, (jsize)written);
        if (out && written) (*env)->SetByteArrayRegion(env, out, 0, (jsize)written, (const jbyte *)buf);
    }
    free(buf);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_hyperlinkAt(
        JNIEnv *env, jclass cls, jlong handle, jint screenRow, jint col) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || screenRow < 0) return NULL;
    vt->maybe_restored_cold_page = true;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint point;
    if (!make_point(vt, screenRow, col, &point)) return NULL;
    if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) return NULL;
    size_t len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len) != GHOSTTY_OUT_OF_SPACE || len == 0) return NULL;
    uint8_t *buf = (uint8_t *)malloc(len);
    if (!buf) return NULL;
    if (ghostty_grid_ref_hyperlink_uri(&ref, buf, len, &len) != GHOSTTY_SUCCESS) { free(buf); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_hyperlinkAtScreen(
        JNIEnv *env, jclass cls, jlong handle, jint screenRow, jint col) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || screenRow < 0 || col < 0 || col >= vt->cols) return NULL;
    vt->maybe_restored_cold_page = true;
    GhosttyPoint point = {0};
    point.tag = GHOSTTY_POINT_TAG_SCREEN;
    point.value.coordinate.x = (uint16_t)col;
    point.value.coordinate.y = (uint32_t)screenRow;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) return NULL;
    GhosttyCell cell = 0;
    if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS || cell == 0) return NULL;
    GhosttyCellContentTag content = GHOSTTY_CELL_CONTENT_CODEPOINT;
    if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &content) != GHOSTTY_SUCCESS) return NULL;
    if (content == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE || content == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) return NULL;
    bool hasText = false;
    if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_TEXT, &hasText) != GHOSTTY_SUCCESS || !hasText) return NULL;
    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    if (ghostty_grid_ref_style(&ref, &style) != GHOSTTY_SUCCESS || style.invisible) return NULL;
    bool hasLink = false;
    if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &hasLink) != GHOSTTY_SUCCESS || !hasLink) return NULL;
    size_t len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len) != GHOSTTY_OUT_OF_SPACE || len == 0) return NULL;
    uint8_t *buf = (uint8_t *)malloc(len);
    if (!buf) return NULL;
    if (ghostty_grid_ref_hyperlink_uri(&ref, buf, len, &len) != GHOSTTY_SUCCESS) { free(buf); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_rowBlankFlags(
        JNIEnv *env, jclass cls, jlong handle, jint startRow, jint endRow) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || startRow < 0 || endRow < startRow || (uint64_t)(endRow - startRow) > 1000000u) return NULL;
    vt->maybe_restored_cold_page = true;
    int count = endRow - startRow + 1;
    jint *values = (jint *)calloc((size_t)count, sizeof(jint));
    if (!values) return NULL;
    for (int y = startRow; y <= endRow; y++) {
        int blank = 1;
        for (int x = 0; x < vt->cols && blank; x++) {
            GhosttyPoint point = {0};
            point.tag = GHOSTTY_POINT_TAG_SCREEN;
            point.value.coordinate.x = (uint16_t)x;
            point.value.coordinate.y = (uint32_t)y;
            GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
            if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) continue;
            GhosttyCell cell = 0;
            if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS || cell == 0) continue;
            bool hasText = false;
            if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_TEXT, &hasText) != GHOSTTY_SUCCESS || !hasText) continue;
            uint32_t cp = 0;
            if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp) != GHOSTTY_SUCCESS) continue;
            if (cp != 0 && cp != ' ') blank = 0;
        }
        values[y - startRow] = blank;
    }
    jintArray out = (*env)->NewIntArray(env, count);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, count, values);
    free(values);
    return out;
}

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_selectWordAt(
        JNIEnv *env, jclass cls, jlong handle, jint screenRow, jint col) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || screenRow < 0 || col < 0 || col >= vt->cols) return NULL;
    vt->maybe_restored_cold_page = true;
    GhosttyPoint point = {0};
    point.tag = GHOSTTY_POINT_TAG_SCREEN;
    point.value.coordinate.x = (uint16_t)col;
    point.value.coordinate.y = (uint32_t)screenRow;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) return NULL;
    GhosttyTerminalSelectWordOptions opt = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
    opt.ref = ref;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_select_word(vt->terminal, &opt, &sel) != GHOSTTY_SUCCESS) return NULL;
    GhosttyPointCoordinate start = {0}, end = {0};
    if (ghostty_terminal_point_from_grid_ref(vt->terminal, &sel.start, GHOSTTY_POINT_TAG_SCREEN, &start) != GHOSTTY_SUCCESS ||
        ghostty_terminal_point_from_grid_ref(vt->terminal, &sel.end, GHOSTTY_POINT_TAG_SCREEN, &end) != GHOSTTY_SUCCESS) return NULL;
    jint values[4] = {(jint)start.x, (jint)start.y, (jint)end.x, (jint)end.y};
    jintArray out = (*env)->NewIntArray(env, 4);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_cellWideAtScreen(
        JNIEnv *env, jclass cls, jlong handle, jint screenRow, jint col) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || screenRow < 0 || col < 0 || col >= vt->cols) return -1;
    vt->maybe_restored_cold_page = true;
    GhosttyPoint point = {0};
    point.tag = GHOSTTY_POINT_TAG_SCREEN;
    point.value.coordinate.x = (uint16_t)col;
    point.value.coordinate.y = (uint32_t)screenRow;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(vt->terminal, point, &ref) != GHOSTTY_SUCCESS) return -1;
    GhosttyCell cell = 0;
    if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS || cell == 0) return -1;
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide) != GHOSTTY_SUCCESS) return -1;
    if (wide == GHOSTTY_CELL_WIDE_WIDE) return 1;
    if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) return 2;
    return 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_reset(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return -1;
    vt->metadata_count = 0; vt->reply_len = 0; vt->reply_overflow = false;
    vt->call_env = env; ghostty_terminal_reset(vt->terminal); vt->call_env = NULL;
    vt->last_compressed_pages = 0; vt->maybe_restored_cold_page = false;
    vt->force_full_snapshot = true; vt->palette_valid = false; vt->cluster_bulk_ready = false;
    return 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_cancelHold(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return -1;
    GhosttyTerminalModeConfig mode = {.mode = ghostty_mode_new(2026, false), .value = false};
    if (ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_MODE, &mode) != GHOSTTY_SUCCESS) return -2;
    vt->hold_active = 0; vt->hold_ignored = false; vt->hold_deadline_ms = 0;
    return 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_destroyChecked(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return -1;
    registry_remove(vt);
    if (vt->host) { (*env)->DeleteGlobalRef(env, vt->host); vt->host=NULL; }
    if (vt->row_iter) ghostty_render_state_row_iterator_free(vt->row_iter);
    if (vt->row_cells) ghostty_render_state_row_cells_free(vt->row_cells);
    if (vt->render) ghostty_render_state_free(vt->render);
    if (vt->key_encoder) ghostty_key_encoder_free(vt->key_encoder);
    if (vt->key_event) ghostty_key_event_free(vt->key_event);
    if (vt->terminal) ghostty_terminal_free(vt->terminal);
    free(vt->replies); free(vt->graphemes); free(vt->cluster_bulk); free(vt->snap_buf); free(vt->clipboard); free(vt->iterm_data); free(vt->paste_out); free(vt);
    return 0;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_rowWrap(
        JNIEnv *env, jclass cls, jlong handle, jintArray out) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt || !out) return -1;
    GhosttyRenderState state = vt->render;
    if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &vt->row_iter) != GHOSTTY_SUCCESS)
        return -1;
    jsize cap = (*env)->GetArrayLength(env, out);
    if (cap < vt->rows) return vt->rows;
    int *values = (int *)calloc((size_t)vt->rows, sizeof(int));
    if (!values) return -2;
    while (ghostty_render_state_row_iterator_next(vt->row_iter)) {
        int32_t viewport_y = -1;
        ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &viewport_y);
        if (viewport_y < 0 || viewport_y >= vt->rows) continue;
        GhosttyRow raw = 0;
        bool wrap = false;
        if (ghostty_render_state_row_get(vt->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &raw) == GHOSTTY_SUCCESS)
            ghostty_row_get(raw, GHOSTTY_ROW_DATA_WRAP, &wrap);
        values[viewport_y] = wrap ? 1 : 0;
    }
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)vt->rows, (const jint *)values);
    free(values);
    return vt->rows;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_takeReplies(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)vt->reply_len);
    if (result && vt->reply_len)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)vt->reply_len, (const jbyte *)vt->replies);
    if (result && !(*env)->ExceptionCheck(env)) { vt->reply_len = 0; vt->reply_overflow = false; }
    return result;
}

JNIEXPORT jboolean JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_replyOverflow(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    return vt && vt->reply_overflow ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_renderHold(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    return vt ? vt->hold_active : -1;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_renderHoldEvents(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    LumaVT *vt = registry_get(handle);
    return vt ? vt->hold_events : -1;
}

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_placements(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    GhosttyKittyGraphics graphics = NULL;
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics) != GHOSTTY_SUCCESS || !graphics)
        return NULL;
    GhosttyKittyGraphicsPlacementIterator iter = NULL;
    if (ghostty_kitty_graphics_placement_iterator_new(NULL, &iter) != GHOSTTY_SUCCESS) return NULL;
    ghostty_kitty_graphics_get(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR, &iter);
    int max = 128;
    int *flat = (int *)calloc(1 + max * 11, sizeof(int));
    if (!flat) { ghostty_kitty_graphics_placement_iterator_free(iter); return NULL; }
    int count = 0;
    while (count < max && ghostty_kitty_graphics_placement_next(iter)) {
        uint32_t image_id = 0, placement_id = 0, columns = 0, rows = 0;
        int32_t z = 0, viewport_x = 0, viewport_y = 0;
        uint32_t sx = 0, sy = 0, sw = 0, sh = 0;
        const GhosttyKittyGraphicsPlacementData keys[] = {
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_COLUMNS,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_ROWS,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_SOURCE_X,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_SOURCE_Y,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_SOURCE_WIDTH,
            GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_SOURCE_HEIGHT,
        };
        void *values[] = {&image_id, &placement_id, &z, &columns, &rows, &sx, &sy, &sw, &sh};
        size_t written = 0;
        if (ghostty_kitty_graphics_placement_get_multi(iter, 9, keys, values, &written) != GHOSTTY_SUCCESS) break;
        int *p = flat + 1 + count * 11;
        p[0] = (int)image_id; p[1] = (int)placement_id; p[2] = z;
        p[3] = 0; p[4] = 0;
        p[5] = (int)columns; p[6] = (int)rows;
        p[7] = (int)sx; p[8] = (int)sy; p[9] = (int)sw; p[10] = (int)sh;
        count++;
    }
    ghostty_kitty_graphics_placement_iterator_free(iter);
    flat[0] = count;
    jintArray out = (*env)->NewIntArray(env, 1 + count * 11);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 1 + count * 11, (const jint *)flat);
    free(flat);
    return out;
}

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_imageMeta(
        JNIEnv *env, jclass cls, jlong handle, jint imageId) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    GhosttyKittyGraphics graphics = NULL;
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics) != GHOSTTY_SUCCESS || !graphics)
        return NULL;
    GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(graphics, (uint32_t)imageId);
    if (!image) return NULL;
    uint32_t w = 0, h = 0, format = 0;
    size_t len = 0;
    const uint8_t *pixels = NULL;
    ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &w);
    ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &h);
    ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &format);
    ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, &len);
    if (ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, &pixels) != GHOSTTY_SUCCESS)
        return NULL;
    uint32_t hash = 0x811C9DC5u;
    for (size_t i = 0; i < len; i++) { hash ^= pixels[i]; hash *= 16777619u; }
    jint values[5] = {(jint)w, (jint)h, (jint)format, (jint)hash, (jint)len};
    jintArray out = (*env)->NewIntArray(env, 5);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 5, values);
    return out;
}

JNIEXPORT jlongArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_memoryUsage(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    GhosttyTerminalMemoryUsage usage = GHOSTTY_INIT_SIZED(GhosttyTerminalMemoryUsage);
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_MEMORY_USAGE, &usage) != GHOSTTY_SUCCESS)
        return NULL;
    jlong values[8] = {(jlong)usage.primary_resident_bytes, (jlong)usage.primary_virtual_bytes,
        (jlong)usage.primary_compressed_pages, (jlong)usage.primary_compressed_bytes,
        (jlong)usage.primary_image_bytes, (jlong)usage.primary_pages,
        (jlong)usage.alternate_resident_bytes, usage.compression_supported ? 1 : 0};
    jlongArray out = (*env)->NewLongArray(env, 8);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 8, values);
    return out;
}

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_takeEvents(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle);
    if (!vt) return NULL;
    jintArray out = (*env)->NewIntArray(env, (jsize)vt->metadata_count);
    if (out && vt->metadata_count) (*env)->SetIntArrayRegion(env, out, 0, (jsize)vt->metadata_count, vt->metadata_events);
    if (out) vt->metadata_count = 0;
    return out;
}
JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_stringData(
        JNIEnv *env, jclass cls, jlong handle, jint kind) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    GhosttyString value = {0};
    if (kind == 6) { value.ptr = (const char *)vt->clipboard; value.len = vt->clipboard_len; }
    else if (ghostty_terminal_get(vt->terminal, kind == 1 ? GHOSTTY_TERMINAL_DATA_TITLE : GHOSTTY_TERMINAL_DATA_PWD, &value) != GHOSTTY_SUCCESS)
        return NULL;
    if (value.len > REPLY_CAP) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)value.len);
    if (out && value.len) (*env)->SetByteArrayRegion(env, out, 0, (jsize)value.len, (const jbyte *)value.ptr);
    return out;
}
JNIEXPORT jlongArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_holdState(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    jlong values[3] = {vt->hold_active, (jlong)vt->hold_deadline_ms, vt->hold_events};
    jlongArray out = (*env)->NewLongArray(env, 3);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 3, values);
    return out;
}
JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_ignoreHold(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return -1;
    if (vt->hold_active) { vt->hold_active = 0; vt->hold_ignored = true; return 1; }
    return 0;
}

extern int luma_ghostty_checkpoint(GhosttyTerminal terminal, int32_t *out, size_t len);
JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_checkpoint(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    int32_t state[80];
    if (luma_ghostty_checkpoint(vt->terminal, state, 80) != 0) return NULL;
    uint8_t pending[1024]; size_t len = 0;
    GhosttyResult result = ghostty_terminal_continuation_buf(vt->terminal, pending, sizeof(pending), &len);
    if (result == GHOSTTY_SUCCESS && len > 0) {
        if (pending[0] == 27) {
            state[34] = 1;
            if (len >= 2) {
                uint8_t type = pending[1];
                if (type == '[') {
                    state[34] = 6; size_t pos = 2;
                    if (pos < len && pending[pos] == '?') { state[34] = 7; pos++; }
                    else if (pos < len && pending[pos] == '>') { state[34] = 12; pos++; }
                    int arg = 0;
                    for (; pos < len && arg < 32; pos++) {
                        uint8_t c = pending[pos];
                        if (c >= '0' && c <= '9') {
                            int old = state[38 + arg]; if (old < 0) old = 0;
                            state[38 + arg] = old > 6553 ? 65535 : old * 10 + c - '0';
                        } else if (c == ';' || c == ':') {
                            if (c == ':') state[36] |= (int32_t)(1u << arg);
                            if (arg < 31) arg++;
                        } else if (c == '$') state[34] = state[34] == 7 ? 14 : 8;
                        else if (c == ' ') state[34] = 15;
                        else if (c == '*') state[34] = 16;
                        else if (c == '"') state[34] = 17;
                        else if (c == 39) state[34] = 18;
                        else if (c == '!') state[34] = 19;
                    }
                    state[35] = arg;
                } else if (type == ']') {
                    state[34] = pending[len - 1] == 27 ? 11 : 10;
                    size_t payload = len - 2 - (state[34] == 11 ? 1 : 0);
                    if (payload <= 15 && payload >= 3 && memcmp(pending + 2, "133", 3) == 0) {
                        uint8_t prefix[16] = {0}; prefix[0] = (uint8_t)payload;
                        memcpy(prefix + 1, pending + 2, payload);
                        for (int i = 0; i < 4; i++) state[76 + i] = (int32_t)((uint32_t)prefix[i*4]<<24 |
                            (uint32_t)prefix[i*4+1]<<16 | (uint32_t)prefix[i*4+2]<<8 | prefix[i*4+3]);
                    }
                } else if (type == 'P') { state[34] = 13; state[37] = pending[len-1] == 27; }
                else if (type == '_') state[34] = pending[len-1] == 27 ? 21 : 20;
                else if (type == '(') state[34] = 3;
                else if (type == ')') state[34] = 4;
                else if (type == '#') state[34] = 2;
                else if (type == '%') state[34] = 9;
            }
        } else if (len <= 3 && pending[0] >= 0xc0) {
            uint8_t prefix[4] = {(uint8_t)(0x80 | len), 0, 0, 0}; memcpy(prefix + 1, pending, len);
            state[76] = (int32_t)((uint32_t)prefix[0]<<24 | (uint32_t)prefix[1]<<16 |
                (uint32_t)prefix[2]<<8 | prefix[3]);
        }
    }
    jintArray out = (*env)->NewIntArray(env, 80);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 80, state);
    return out;
}

extern int luma_ghostty_keyboard_checkpoint(GhosttyTerminal terminal, int32_t *out, size_t len);
extern int luma_ghostty_keyboard_restore(GhosttyTerminal terminal, const int32_t *in, size_t len);

JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_keyboardCheckpoint(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    int32_t state[21];
    if (luma_ghostty_keyboard_checkpoint(vt->terminal, state, 21) != 0) return NULL;
    uint8_t pending[128]; size_t len = 0;
    if (ghostty_terminal_continuation_buf(vt->terminal, pending, sizeof(pending), &len) == GHOSTTY_SUCCESS &&
            len >= 3 && pending[0] == 27 && pending[1] == '[') {
        uint8_t intermediate = pending[2];
        if (intermediate == '<' || intermediate == '=' || intermediate == '>' || intermediate == '?')
            state[20] = intermediate;
    }
    jintArray out = (*env)->NewIntArray(env, 21);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 21, state);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_keyboardRestore(
        JNIEnv *env, jclass cls, jlong handle, jintArray words) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt || !words) return -1;
    if ((*env)->GetArrayLength(env, words) != 21) return -1;
    int32_t state[21];
    (*env)->GetIntArrayRegion(env, words, 0, 21, state);
    return luma_ghostty_keyboard_restore(vt->terminal, state, 21);
}

static bool ensure_key_codec(LumaVT *vt) {
    if (!vt->key_encoder && ghostty_key_encoder_new(NULL, &vt->key_encoder) != GHOSTTY_SUCCESS)
        return false;
    if (!vt->key_event && ghostty_key_event_new(NULL, &vt->key_event) != GHOSTTY_SUCCESS)
        return false;
    return true;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_encodeKey(
        JNIEnv *env, jclass cls, jlong handle, jint key, jint action, jint mods,
        jint consumed_mods, jbyteArray utf8, jint unshifted_codepoint, jboolean composing) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    if (action < 0 || action > 2 || !ensure_key_codec(vt)) return NULL;
    uint8_t text[128]; size_t text_len = 0;
    if (utf8) {
        jsize length = (*env)->GetArrayLength(env, utf8);
        if (length < 0 || length > (jsize)sizeof(text)) return NULL;
        if (length > 0) {
            (*env)->GetByteArrayRegion(env, utf8, 0, length, (jbyte *)text);
            text_len = (size_t)length;
        }
    }
    ghostty_key_encoder_setopt_from_terminal(vt->key_encoder, vt->terminal);
    ghostty_key_event_set_action(vt->key_event, (GhosttyKeyAction)action);
    ghostty_key_event_set_key(vt->key_event, (GhosttyKey)key);
    ghostty_key_event_set_mods(vt->key_event, (GhosttyMods)mods);
    ghostty_key_event_set_consumed_mods(vt->key_event, (GhosttyMods)consumed_mods);
    ghostty_key_event_set_composing(vt->key_event, composing ? true : false);
    ghostty_key_event_set_utf8(vt->key_event, (const char *)text, text_len);
    ghostty_key_event_set_unshifted_codepoint(vt->key_event, (uint32_t)unshifted_codepoint);

    uint8_t fixed[256];
    uint8_t *data = fixed;
    size_t written = 0;
    GhosttyResult result = ghostty_key_encoder_encode(vt->key_encoder, vt->key_event,
        (char *)data, sizeof(fixed), &written);
    bool heap = false;
    if (result == GHOSTTY_OUT_OF_SPACE && written > 0 && written <= 4096) {
        data = (uint8_t *)malloc(written);
        if (!data) return NULL;
        heap = true;
        result = ghostty_key_encoder_encode(vt->key_encoder, vt->key_event,
            (char *)data, written, &written);
    }
    if (result != GHOSTTY_SUCCESS) {
        if (heap) free(data);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)written);
    if (out && written > 0)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)written, (const jbyte *)data);
    if (heap) free(data);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_setSelection(
        JNIEnv *env, jclass cls, jlong handle, jint x1, jint y1, jint x2, jint y2, jboolean clear) {
    (void)env; (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return -1;
    if (clear) return ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, NULL) == GHOSTTY_SUCCESS ? 0 : -1;
    GhosttyTerminalScrollbar bar = {0}; ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    int history = (int)(bar.total - bar.len);
    if (x1 < 0 || x2 < 0 || x1 >= vt->cols || x2 >= vt->cols || y1 < -history || y2 < -history || y1 >= vt->rows || y2 >= vt->rows) return -1;
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyPoint a = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value.coordinate = {.x = (uint16_t)x1, .y = (uint32_t)(y1 + history)}};
    GhosttyPoint b = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value.coordinate = {.x = (uint16_t)x2, .y = (uint32_t)(y2 + history)}};
    selection.start.size = sizeof(selection.start); selection.end.size = sizeof(selection.end);
    if (ghostty_terminal_grid_ref(vt->terminal, a, &selection.start) != GHOSTTY_SUCCESS ||
            ghostty_terminal_grid_ref(vt->terminal, b, &selection.end) != GHOSTTY_SUCCESS) return -1;
    return ghostty_terminal_set(vt->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS ? 0 : -1;
}
extern int luma_ghostty_selection_valid(GhosttyTerminal terminal);
JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_selection(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return NULL;
    if (!luma_ghostty_selection_valid(vt->terminal)) return NULL;
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &selection) != GHOSTTY_SUCCESS) return NULL;
    GhosttyPointCoordinate a = {0}, b = {0};
    if (ghostty_terminal_point_from_grid_ref(vt->terminal, &selection.start, GHOSTTY_POINT_TAG_SCREEN, &a) != GHOSTTY_SUCCESS ||
            ghostty_terminal_point_from_grid_ref(vt->terminal, &selection.end, GHOSTTY_POINT_TAG_SCREEN, &b) != GHOSTTY_SUCCESS) return NULL;
    GhosttyTerminalScrollbar bar = {0}; ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    int history = (int)(bar.total - bar.len);
    jint values[4] = {a.x, (int)a.y - history, b.x, (int)b.y - history};
    jintArray out = (*env)->NewIntArray(env, 4); if (out) (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

static JavaVM *g_vm;
static jclass g_decoder;
static jmethodID g_decode_png;
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved; g_vm = vm; return JNI_VERSION_1_6;
}
static bool decode_android_png(void *userdata, const GhosttyAllocator *allocator,
        const uint8_t *data, size_t len, GhosttySysImage *out) {
    (void)userdata; JNIEnv *env = NULL;
    if (!g_vm || !g_decoder || len > 8u*1024u*1024u || (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return false;
    jbyteArray encoded = (*env)->NewByteArray(env, (jsize)len); if (!encoded) return false;
    (*env)->SetByteArrayRegion(env, encoded, 0, (jsize)len, (const jbyte *)data);
    jintArray decoded = (jintArray)(*env)->CallStaticObjectMethod(env, g_decoder, g_decode_png, encoded);
    (*env)->DeleteLocalRef(env, encoded);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return false; }
    if (!decoded || (*env)->GetArrayLength(env, decoded) < 2) return false;
    jint dims[2]; (*env)->GetIntArrayRegion(env, decoded, 0, 2, dims);
    uint64_t count = (uint64_t)(uint32_t)dims[0]*(uint32_t)dims[1];
    if (dims[0] <= 0 || dims[1] <= 0 || count > 2u*1024u*1024u || count+2 != (uint64_t)(*env)->GetArrayLength(env,decoded)) {
        (*env)->DeleteLocalRef(env,decoded); return false;
    }
    size_t bytes = (size_t)count*4; uint8_t *rgba = ghostty_alloc(allocator,bytes);
    if (!rgba) { (*env)->DeleteLocalRef(env,decoded); return false; }
    jint *argb = (*env)->GetIntArrayElements(env,decoded,NULL);
    if (!argb) { ghostty_free(allocator,rgba,bytes); (*env)->DeleteLocalRef(env,decoded); return false; }
    for (size_t i=0;i<count;i++) {
        uint32_t pixel=(uint32_t)argb[i+2]; rgba[i*4]=(uint8_t)(pixel>>16); rgba[i*4+1]=(uint8_t)(pixel>>8);
        rgba[i*4+2]=(uint8_t)pixel; rgba[i*4+3]=(uint8_t)(pixel>>24);
    }
    (*env)->ReleaseIntArrayElements(env,decoded,argb,JNI_ABORT); (*env)->DeleteLocalRef(env,decoded);
    out->width=(uint32_t)dims[0];out->height=(uint32_t)dims[1];out->data=rgba;out->data_len=bytes;return true;
}
JNIEXPORT void JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_installDecoder(JNIEnv *env,jclass cls) {
    (void)cls; if (g_decoder) return;
    jclass decoder=(*env)->FindClass(env,"io/github/modao7067/luma/ghostty/GhosttyImageDecoder"); if (!decoder) return;
    g_decoder=(*env)->NewGlobalRef(env,decoder);(*env)->DeleteLocalRef(env,decoder);
    g_decode_png=(*env)->GetStaticMethodID(env,g_decoder,"decodePng","([B)[I");
    if (g_decode_png) ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG,(const void *)decode_android_png);
}
JNIEXPORT void JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_bindHost(JNIEnv *env,jclass cls,jlong handle,jobject host) {
    (void)cls;LumaVT *vt=registry_get(handle);if(!vt)return;
    if(vt->host)(*env)->DeleteGlobalRef(env,vt->host);
    vt->host=host?(*env)->NewGlobalRef(env,host):NULL;
    if(host){jclass type=(*env)->GetObjectClass(env,host);vt->host_hold=(*env)->GetMethodID(env,type,"onRenderHold","(Z)V");vt->host_replies=(*env)->GetMethodID(env,type,"onNativeReplies","([B)V");(*env)->DeleteLocalRef(env,type);}
}
extern int64_t luma_ghostty_animation_tick(GhosttyTerminal terminal,uint64_t now_ms);
JNIEXPORT jlong JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_animationTick(JNIEnv *env,jclass cls,jlong handle,jlong now) {
    (void)env;(void)cls;LumaVT *vt=registry_get(handle);if(!vt||vt->hold_active)return -1;
    return luma_ghostty_animation_tick(vt->terminal,(uint64_t)now);
}
JNIEXPORT jlongArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_imagePlacements(JNIEnv *env,jclass cls,jlong handle) {
    (void)cls;LumaVT *vt=registry_get(handle);if(!vt)return NULL;
    GhosttyKittyGraphics graphics=NULL;
    ghostty_terminal_get(vt->terminal,GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,&graphics);
    const int stride=16,max=1024; int capacity=16;
    jlong *flat=calloc(2+(size_t)capacity*stride,sizeof(jlong));if(!flat)return NULL;
    uint64_t storage_generation=0;int count=0;
    if(graphics){
        ghostty_kitty_graphics_get(graphics,GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION,&storage_generation);
        GhosttyKittyGraphicsPlacementIterator iterator=NULL;
        if(ghostty_kitty_graphics_placement_iterator_new(NULL,&iterator)==GHOSTTY_SUCCESS){
            ghostty_kitty_graphics_get(graphics,GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR,&iterator);
            while(count<max&&ghostty_kitty_graphics_placement_next(iterator)){
                uint32_t id=0,placement=0,offx=0,offy=0,width=0,height=0;int32_t z=0;
                ghostty_kitty_graphics_placement_get(iterator,GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,&id);
                GhosttyKittyGraphicsImage image=ghostty_kitty_graphics_image(graphics,id);
                if(!image)continue;
                GhosttyKittyGraphicsPlacementRenderInfo info=GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
                if(ghostty_kitty_graphics_placement_render_info(iterator,image,vt->terminal,&info)!=GHOSTTY_SUCCESS||!info.viewport_visible)continue;
                ghostty_kitty_graphics_placement_get(iterator,GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID,&placement);
                ghostty_kitty_graphics_placement_get(iterator,GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z,&z);
                ghostty_kitty_graphics_placement_get(iterator,GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET,&offx);
                ghostty_kitty_graphics_placement_get(iterator,GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET,&offy);
                uint64_t generation=0;
                ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_GENERATION,&generation);
                ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_WIDTH,&width);
                ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_HEIGHT,&height);
                if (count == capacity) {
                    capacity *= 2; jlong *grown = realloc(flat,(2+(size_t)capacity*stride)*sizeof(jlong));
                    if (!grown) break; flat = grown;
                }
                jlong *p=flat+2+(size_t)count*stride;
                p[0]=id;p[1]=placement;p[2]=z;p[3]=info.viewport_col;p[4]=info.viewport_row;p[5]=offx;p[6]=offy;
                p[7]=info.pixel_width;p[8]=info.pixel_height;p[9]=info.source_x;p[10]=info.source_y;
                p[11]=info.source_width;p[12]=info.source_height;p[13]=(jlong)generation;p[14]=width;p[15]=height;count++;
            }
            ghostty_kitty_graphics_placement_iterator_free(iterator);
        }
    }
    flat[0]=(jlong)storage_generation;flat[1]=count;
    jlongArray out=(*env)->NewLongArray(env,2+count*stride);
    if(out)(*env)->SetLongArrayRegion(env,out,0,2+count*stride,flat);free(flat);return out;
}
JNIEXPORT jintArray JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_imagePixels(JNIEnv *env,jclass cls,jlong handle,jint id) {
    (void)cls;LumaVT *vt=registry_get(handle);if(!vt)return NULL;
    GhosttyKittyGraphics graphics=NULL;GhosttyKittyGraphicsImage image=NULL;
    ghostty_terminal_get(vt->terminal,GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,&graphics);
    if(!graphics)return NULL;
    image=ghostty_kitty_graphics_image(graphics,(uint32_t)id);if(!image)return NULL;
    uint32_t width=0,height=0;GhosttyKittyImageFormat format;size_t len=0;const uint8_t *pixels=NULL;
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_WIDTH,&width);ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_HEIGHT,&height);
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_FORMAT,&format);ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,&pixels);ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,&len);
    size_t count=(size_t)width*height;int channels=format==GHOSTTY_KITTY_IMAGE_FORMAT_RGB?3:format==GHOSTTY_KITTY_IMAGE_FORMAT_RGBA?4:format==GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA?2:format==GHOSTTY_KITTY_IMAGE_FORMAT_GRAY?1:0;
    if(!pixels||!width||!height||!channels||count>4u*1024u*1024u||len!=count*(size_t)channels)return NULL;
    jint *argb=malloc((count+2)*sizeof(jint));if(!argb)return NULL;argb[0]=(jint)width;argb[1]=(jint)height;
    for(size_t i=0;i<count;i++){
        const uint8_t *p=pixels+i*(size_t)channels;uint32_t r=p[0],g=channels>=3?p[1]:p[0],b=channels>=3?p[2]:p[0],a=channels==4?p[3]:channels==2?p[1]:255;
        argb[i+2]=(jint)(a<<24|r<<16|g<<8|b);
    }
    jintArray out=(*env)->NewIntArray(env,(jsize)count+2);if(out)(*env)->SetIntArrayRegion(env,out,0,(jsize)count+2,argb);free(argb);return out;
}

JNIEXPORT jboolean JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_copyImage(JNIEnv *env,jclass cls,jlong handle,jint id,jobject target) {
    (void)cls; LumaVT *vt=registry_get(handle); if(!vt||!target)return JNI_FALSE;
    GhosttyKittyGraphics graphics=NULL;
    ghostty_terminal_get(vt->terminal,GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,&graphics);
    if(!graphics)return JNI_FALSE;
    GhosttyKittyGraphicsImage image=ghostty_kitty_graphics_image(graphics,(uint32_t)id);
    if(!image)return JNI_FALSE;
    uint32_t width=0,height=0; GhosttyKittyImageFormat format; size_t len=0; const uint8_t *pixels=NULL;
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_WIDTH,&width);
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_HEIGHT,&height);
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_FORMAT,&format);
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,&pixels);
    ghostty_kitty_graphics_image_get(image,GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,&len);
    uint64_t count=(uint64_t)width*height;
    int channels=format==GHOSTTY_KITTY_IMAGE_FORMAT_RGB?3:format==GHOSTTY_KITTY_IMAGE_FORMAT_RGBA?4:format==GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA?2:format==GHOSTTY_KITTY_IMAGE_FORMAT_GRAY?1:0;
    AndroidBitmapInfo info;
    if(!pixels||!width||!height||!channels||count>4u*1024u*1024u||len!=count*(unsigned)channels||
       AndroidBitmap_getInfo(env,target,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||info.width!=width||info.height!=height||info.stride<width*4u)return JNI_FALSE;
    void *buffer=NULL;
    if(AndroidBitmap_lockPixels(env,target,&buffer)!=ANDROID_BITMAP_RESULT_SUCCESS)return JNI_FALSE;
    for(uint32_t y=0;y<height;y++) {
        uint8_t *out=(uint8_t *)buffer+(size_t)y*info.stride;
        const uint8_t *in=pixels+(size_t)y*width*(unsigned)channels;
        uint32_t x=0;
#if defined(__aarch64__)
        if(channels==4) {
            for(;x+16<=width;x+=16,in+=64,out+=64) {
                uint8x16x4_t rgba=vld4q_u8(in);
                uint8_t min_a=vminvq_u8(rgba.val[3]);
                if(min_a==255) {
                    vst4q_u8(out,rgba);
                } else if(vmaxvq_u8(rgba.val[3])==0) {
                    memset(out,0,64);
                } else {
                    for(uint32_t k=0;k<16;k++) {
                        unsigned a=in[k*4+3];
                        out[k*4+0]=(uint8_t)((in[k*4+0]*a+127)/255);
                        out[k*4+1]=(uint8_t)((in[k*4+1]*a+127)/255);
                        out[k*4+2]=(uint8_t)((in[k*4+2]*a+127)/255);
                        out[k*4+3]=(uint8_t)a;
                    }
                }
            }
        }
#endif
        for(;x<width;x++,in+=channels,out+=4) {
            unsigned a=channels==4?in[3]:channels==2?in[1]:255;
            if(a==255&&channels==4) { memcpy(out,in,4); continue; }
            if(a==0) { memset(out,0,4); continue; }
            out[0]=(uint8_t)((in[0]*a+127)/255);
            out[1]=(uint8_t)(((channels>=3?in[1]:in[0])*a+127)/255);
            out[2]=(uint8_t)(((channels>=3?in[2]:in[0])*a+127)/255);
            out[3]=(uint8_t)a;
        }
    }
    return AndroidBitmap_unlockPixels(env,target)==ANDROID_BITMAP_RESULT_SUCCESS?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_setDefaultCursor(JNIEnv *env,jclass cls,jlong handle,jint style) {
    (void)env;(void)cls;LumaVT *vt=registry_get(handle);if(!vt)return -1;
    GhosttyTerminalCursorStyle value=style==1?GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE:style==2?GHOSTTY_TERMINAL_CURSOR_STYLE_BAR:GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK;
    return ghostty_terminal_set(vt->terminal,GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE,&value)==GHOSTTY_SUCCESS?0:-1;
}

extern int luma_ghostty_inline_add(GhosttyTerminal terminal,const uint8_t *rgba,uint32_t width,uint32_t height);
static int inline_dimension(const char *value,uint32_t cell,int count) {
    char *end=NULL; long number=strtol(value,&end,10);
    if (end==value || number<=0) return 0;
    if (number>1638400) return -1;
    uint64_t pixels=(uint64_t)number;
    if (!strcmp(end,"%")) pixels=pixels*cell*(uint32_t)count/100;
    else if (!strcmp(end,"px")) {} else if (*end==0) pixels*=cell; else return 0;
    return pixels>16384?-1:(int)(pixels?pixels:1);
}
static void finish_inline_image(LumaVT *vt) {
    if (!vt->call_env || !g_decoder || vt->iterm_invalid || !vt->iterm_len) goto cleanup;
    JNIEnv *env=vt->call_env;
    uint32_t pixel_width=0,pixel_height=0;
    ghostty_terminal_get(vt->terminal,GHOSTTY_TERMINAL_DATA_WIDTH_PX,&pixel_width);
    ghostty_terminal_get(vt->terminal,GHOSTTY_TERMINAL_DATA_HEIGHT_PX,&pixel_height);
    int width=0,height=0;bool inline_image=false,preserve=true;char *save=NULL;
    for (char *part=strtok_r(vt->iterm_args,";",&save);part;part=strtok_r(NULL,";",&save)) {
        if(!strncmp(part,"inline=",7))inline_image=!strcmp(part+7,"1");
        else if(!strncmp(part,"width=",6))width=inline_dimension(part+6,pixel_width/(uint32_t)vt->cols,vt->cols);
        else if(!strncmp(part,"height=",7))height=inline_dimension(part+7,pixel_height/(uint32_t)vt->rows,vt->rows);
        else if(!strncmp(part,"preserveAspectRatio=",20))preserve=strcmp(part+20,"0")!=0;
    }
    if(!inline_image||width<0||height<0)goto cleanup;
    jmethodID decode=(*env)->GetStaticMethodID(env,g_decoder,"decodeBase64Image","([BIIZ)[I");if(!decode)goto cleanup;
    jbyteArray data=(*env)->NewByteArray(env,(jsize)vt->iterm_len);if(!data)goto cleanup;
    (*env)->SetByteArrayRegion(env,data,0,(jsize)vt->iterm_len,(const jbyte *)vt->iterm_data);
    jintArray decoded=(jintArray)(*env)->CallStaticObjectMethod(env,g_decoder,decode,data,width,height,preserve?JNI_TRUE:JNI_FALSE);
    (*env)->DeleteLocalRef(env,data);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);goto cleanup;}
    if(!decoded)goto cleanup;
    jint dims[2];jsize length=(*env)->GetArrayLength(env,decoded);
    if(length<2){(*env)->DeleteLocalRef(env,decoded);goto cleanup;}
    (*env)->GetIntArrayRegion(env,decoded,0,2,dims);uint64_t count=(uint64_t)(uint32_t)dims[0]*(uint32_t)dims[1];
    if(dims[0]<=0||dims[1]<=0||count>4u*1024u*1024u||count+2!=(uint64_t)length){(*env)->DeleteLocalRef(env,decoded);goto cleanup;}
    jint *pixels=(*env)->GetIntArrayElements(env,decoded,NULL);uint8_t *rgba=malloc((size_t)count*4);
    if(pixels&&rgba){for(size_t i=0;i<count;i++){uint32_t c=(uint32_t)pixels[i+2];rgba[i*4]=(uint8_t)(c>>16);rgba[i*4+1]=(uint8_t)(c>>8);rgba[i*4+2]=(uint8_t)c;rgba[i*4+3]=(uint8_t)(c>>24);}
        luma_ghostty_inline_add(vt->terminal,rgba,(uint32_t)dims[0],(uint32_t)dims[1]);}
    free(rgba);if(pixels)(*env)->ReleaseIntArrayElements(env,decoded,pixels,JNI_ABORT);(*env)->DeleteLocalRef(env,decoded);
cleanup:
    free(vt->iterm_data);vt->iterm_data=NULL;vt->iterm_len=vt->iterm_capacity=0;vt->iterm_args[0]=0;vt->iterm_invalid=false;
}
static bool append_inline_data(LumaVT *vt,const uint8_t *data,size_t len) {
    const size_t limit=8u*1024u*1024u;
    if(vt->iterm_invalid||len>limit-vt->iterm_len){vt->iterm_invalid=true;return false;}
    size_t need=vt->iterm_len+len;
    if(need>vt->iterm_capacity){size_t cap=vt->iterm_capacity?vt->iterm_capacity*2:4096;if(cap<need)cap=need;if(cap>limit)cap=limit;
        uint8_t *grown=realloc(vt->iterm_data,cap);if(!grown){vt->iterm_invalid=true;return false;}vt->iterm_data=grown;vt->iterm_capacity=cap;}
    if(len)memcpy(vt->iterm_data+vt->iterm_len,data,len);vt->iterm_len+=len;return true;
}
static void on_inline_image(GhosttyTerminal terminal,void *userdata,const GhosttyTerminalUnknownSequence *sequence) {
    (void)terminal;LumaVT *vt=userdata;
    if(sequence->tag!=GHOSTTY_TERMINAL_UNKNOWN_SEQUENCE_OSC||sequence->value.osc.truncated)return;
    const uint8_t *data=(const uint8_t *)sequence->value.osc.content.ptr;size_t len=sequence->value.osc.content.len;
    if(len<5||memcmp(data,"1337;",5))return;data+=5;len-=5;
    if(len>=5&&!memcmp(data,"File=",5)){
        free(vt->iterm_data);vt->iterm_data=NULL;vt->iterm_len=vt->iterm_capacity=0;vt->iterm_invalid=false;vt->iterm_args[0]=0;
        data+=5;len-=5;const uint8_t *colon=memchr(data,':',len);if(!colon)return;size_t args=(size_t)(colon-data);
        if(args>=sizeof(vt->iterm_args))return;memcpy(vt->iterm_args,data,args);vt->iterm_args[args]=0;
        append_inline_data(vt,colon+1,len-args-1);finish_inline_image(vt);
    }else if(len>=14&&!memcmp(data,"MultipartFile=",14)){
        free(vt->iterm_data);vt->iterm_data=NULL;vt->iterm_len=vt->iterm_capacity=0;vt->iterm_invalid=false;
        size_t args=len-14;if(args>=sizeof(vt->iterm_args)){vt->iterm_invalid=true;return;}memcpy(vt->iterm_args,data+14,args);vt->iterm_args[args]=0;
    }else if(len>=9&&!memcmp(data,"FilePart=",9)){
        if(vt->iterm_args[0])append_inline_data(vt,data+9,len-9);
    }else if(len==7&&!memcmp(data,"FileEnd",7))finish_inline_image(vt);
}

extern void luma_ghostty_compression_touch(GhosttyTerminal terminal);
JNIEXPORT jlong JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_compressionActivity(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; LumaVT *vt = registry_get(handle); uint64_t token = 0;
    if (!vt) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) { (*env)->ThrowNew(env, error, "compression activity requires a live owner-thread terminal"); (*env)->DeleteLocalRef(env,error); }
        return 0;
    }
    if (vt->last_compressed_pages > 0 && vt->maybe_restored_cold_page) {
        vt->maybe_restored_cold_page = false;
        GhosttyTerminalMemoryUsage usage = GHOSTTY_INIT_SIZED(GhosttyTerminalMemoryUsage);
        if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_MEMORY_USAGE, &usage) == GHOSTTY_SUCCESS) {
            if (usage.primary_compressed_pages < vt->last_compressed_pages) {
                luma_ghostty_compression_touch(vt->terminal);
            }
            vt->last_compressed_pages = usage.primary_compressed_pages;
        }
    }
    if (ghostty_terminal_compression_activity(vt->terminal, &token) != GHOSTTY_SUCCESS) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) { (*env)->ThrowNew(env, error, "compression activity requires a live owner-thread terminal"); (*env)->DeleteLocalRef(env,error); }
        return 0;
    }
    return (jlong)token;
}
JNIEXPORT jint JNICALL Java_io_github_modao7067_luma_ghostty_GhosttyNative_compressStep(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; LumaVT *vt = registry_get(handle); if (!vt) return -1;
    GhosttyTerminalCompressionResult result;
    if (ghostty_terminal_compress(vt->terminal, GHOSTTY_TERMINAL_COMPRESSION_MODE_INCREMENTAL, &result) != GHOSTTY_SUCCESS) return -1;
    GhosttyTerminalMemoryUsage usage = GHOSTTY_INIT_SIZED(GhosttyTerminalMemoryUsage);
    if (ghostty_terminal_get(vt->terminal, GHOSTTY_TERMINAL_DATA_MEMORY_USAGE, &usage) == GHOSTTY_SUCCESS)
        vt->last_compressed_pages = usage.primary_compressed_pages;
    return (jint)result;
}
