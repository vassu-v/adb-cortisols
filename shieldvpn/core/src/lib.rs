//! ShieldVPN core: JNI wrapper around `adblock` (brave/adblock-rust).
//!
//! Kotlin class: `app.shieldvpn.core.Engine`
//!   - `nativeCreate(ByteArray): Long`   -> opaque handle, 0 on any failure
//!   - `nativeShouldBlock(Long, String): Boolean` -> false on any failure
//!   - `nativeDestroy(Long)`             -> frees the handle
//!
//! Ownership: `nativeCreate` moves a `Box<Handle>` to the caller as a raw pointer
//! (`Box::into_raw`). The caller owns it and must call `nativeDestroy` exactly once;
//! it must not call `nativeShouldBlock` concurrently with or after `nativeDestroy`
//! (the Kotlin wrapper should enforce this, e.g. with a read/write lock).
//! As defence in depth, live handles are tracked in a registry, so a double
//! destroy, a stale handle or a forged value is ignored instead of freed twice.
//! No panic ever crosses the FFI boundary (`catch_unwind`; panic = "unwind").

use adblock::lists::{FilterSet, ParseOptions, RuleTypes};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

pub const MAX_RULES_BYTES: usize = 32 * 1024 * 1024;
pub const MAX_HOST_BYTES: usize = 255;

/// Page the pretend request comes from; never equals a real host (`.invalid` is reserved).
const THIRD_PARTY_SOURCE: &str = "https://page.shieldvpn.invalid/";
const PROBE_TYPES: [&str; 5] = ["script", "xmlhttprequest", "image", "ping", "other"];

struct Handle {
    engine: Engine,
}

// Compile-time check that Engine can be shared across JNI threads.
const _: fn() = || {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<Handle>();
};

/// Addresses of live handles. Lets us reject stale/double-freed/forged values.
static LIVE: Mutex<Option<HashSet<usize>>> = Mutex::new(None);

fn with_live<R>(f: impl FnOnce(&mut HashSet<usize>) -> R) -> R {
    let mut g = LIVE.lock().unwrap_or_else(|e| e.into_inner());
    f(g.get_or_insert_with(HashSet::new))
}

/// Build an engine from raw filter-list bytes. Invalid UTF-8 is replaced lossily;
/// unparseable rules are skipped by the parser. Returns 0 on failure.
pub fn create(rules: &[u8]) -> usize {
    if rules.len() > MAX_RULES_BYTES {
        return 0;
    }
    create_owned(rules.to_vec())
}

/// Same as `create` but takes ownership, avoiding a second copy of the list text
/// when the bytes are already valid UTF-8 (the common case).
pub fn create_owned(rules: Vec<u8>) -> usize {
    if rules.len() > MAX_RULES_BYTES {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let text = match String::from_utf8(rules) {
            Ok(s) => s, // no copy
            Err(e) => String::from_utf8_lossy(e.as_bytes()).into_owned(),
        }; // the original Vec is dropped here when the lossy path was taken
        let mut fs = FilterSet::new(false);
        let opts = ParseOptions {
            rule_types: RuleTypes::NetworkOnly,
            ..ParseOptions::default()
        };
        fs.add_filter_list(text, opts);
        let engine = Engine::new_with_filter_set(fs);
        let ptr = Box::into_raw(Box::new(Handle { engine })) as usize;
        with_live(|s| s.insert(ptr));
        ptr
    }))
    .unwrap_or(0)
}

fn valid_host(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= MAX_HOST_BYTES
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

/// Returns true only if the engine decides to block (matched, no exception).
pub fn should_block(handle: usize, host: &str) -> bool {
    if handle == 0 || !valid_host(host) {
        return false;
    }
    catch_unwind(AssertUnwindSafe(|| {
        // Hold the registry lock only to validate; Handle lifetime is the caller's contract.
        if !with_live(|s| s.contains(&handle)) {
            return false;
        }
        // SAFETY: `handle` is in LIVE, so it came from Box::into_raw in `create` and has
        // not been freed. The caller guarantees no concurrent `destroy` (see module docs).
        // Only a shared reference is created; Engine is Send + Sync.
        let h = unsafe { &*(handle as *const Handle) };
        let url = format!("https://{}/", host.to_ascii_lowercase());
        // A DNS lookup carries no page context, so model it as a request made by some other
        // site. Without this, every `$third-party` rule (most tracker rules in Brave's and
        // EasyPrivacy's lists) can never match. Resource type is unknown too, so a host is
        // blocked if a rule would block it as any common subresource.
        PROBE_TYPES.iter().any(|t| match Request::new(&url, THIRD_PARTY_SOURCE, t, "GET") {
            Ok(req) => h.engine.check_network_request(&req).should_block(),
            Err(_) => false,
        })
    }))
    .unwrap_or(false)
}

/// Frees the handle if (and only if) it is live. Idempotent for stale values.
pub fn destroy(handle: usize) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if with_live(|s| s.remove(&handle)) {
            // SAFETY: the address was in LIVE (created by Box::into_raw in `create`) and we
            // just removed it, so this is the single place that reclaims it: no double free.
            drop(unsafe { Box::from_raw(handle as *mut Handle) });
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_app_shieldvpn_core_Engine_nativeCreate<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    rules: JByteArray<'l>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if rules.is_null() {
            return 0;
        }
        match env.get_array_length(&rules) {
            Ok(n) if n >= 0 && (n as usize) <= MAX_RULES_BYTES => {}
            _ => return 0,
        }
        match env.convert_byte_array(&rules) {
            Ok(bytes) => create_owned(bytes) as jlong,
            Err(_) => 0,
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_app_shieldvpn_core_Engine_nativeShouldBlock<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    host: JString<'l>,
) -> jboolean {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 || host.is_null() {
            return false;
        }
        let s: String = match env.get_string(&host) {
            Ok(s) => s.into(),
            Err(_) => return false,
        };
        should_block(handle as usize, &s)
    }))
    .unwrap_or(false);
    if r {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_shieldvpn_core_Engine_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    destroy(handle as usize);
}

#[cfg(test)]
mod tests {
    use super::*;

    const RULES: &str = "! comment\n||ads.example.com^\n||tracker.net^\n@@||ok.tracker.net^\n||evil.org^\n";

    #[test]
    fn blocks_and_allows() {
        let h = create(RULES.as_bytes());
        assert_ne!(h, 0);
        assert!(should_block(h, "ads.example.com"));
        assert!(should_block(h, "sub.ads.example.com"));
        assert!(should_block(h, "ADS.Example.com"));
        assert!(!should_block(h, "example.com"));
        assert!(!should_block(h, "notads.example.com.au"));
        destroy(h);
    }

    #[test]
    fn exceptions_win() {
        let h = create(RULES.as_bytes());
        assert!(should_block(h, "tracker.net"));
        assert!(should_block(h, "x.tracker.net"));
        assert!(!should_block(h, "ok.tracker.net"));
        destroy(h);
    }

    #[test]
    fn third_party_and_typed_rules_match() {
        let rules = "! t\n||tp.example^$third-party\n||js.example^$script\n||x.example^$image,third-party\n@@||ok.tp.example^$third-party\n";
        let h = create(rules.as_bytes());
        assert!(should_block(h, "tp.example"));
        assert!(should_block(h, "a.tp.example"));
        assert!(should_block(h, "js.example"));
        assert!(should_block(h, "x.example"));
        assert!(!should_block(h, "ok.tp.example"));
        assert!(!should_block(h, "unlisted.example"));
        destroy(h);
    }

    #[test]
    fn garbage_input() {
        let junk: Vec<u8> = (0..4096u32).map(|i| (i.wrapping_mul(2654435761) >> 24) as u8).collect();
        let h = create(&junk);
        assert_ne!(h, 0); // empty-ish engine, must not crash
        assert!(!should_block(h, "example.com"));
        destroy(h);
        let h = create(b"");
        assert!(!should_block(h, "example.com"));
        destroy(h);
        let h = create(b"||a.com^\n\xff\xfe\n||b.com^\n");
        assert!(should_block(h, "b.com"));
        destroy(h);
    }

    #[test]
    fn bad_handles_and_hosts() {
        let h = create(RULES.as_bytes());
        assert!(!should_block(0, "ads.example.com"));
        assert!(!should_block(0xdead_beef, "ads.example.com"));
        assert!(!should_block(h, ""));
        assert!(!should_block(h, &"a".repeat(256)));
        assert!(!should_block(h, "bad host/with?stuff"));
        destroy(0);
        destroy(h);
        destroy(h); // double destroy ignored
        assert!(!should_block(h, "ads.example.com")); // stale handle rejected
    }

    #[test]
    fn oversized_rules_rejected() {
        let big = vec![b'a'; MAX_RULES_BYTES + 1];
        assert_eq!(create(&big), 0);
    }
}
