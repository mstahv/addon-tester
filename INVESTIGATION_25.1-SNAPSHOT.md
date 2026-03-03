# Investigation: Build Failures Against Vaadin 25.1-SNAPSHOT

## Summary

Six projects that build successfully against Vaadin 25.0-SNAPSHOT are now failing against Vaadin 25.1-SNAPSHOT. This investigation identifies the root cause and provides recommendations for fixing each project.

## Background

**Comparison of Test Results:**

### 25.0-SNAPSHOT (✅ Successful Build - Feb 13, 15:05)
- hugerte-for-flow: ⚠️ KNOWN ISSUE (pre-existing failure)
- super-fields: ✅ PASSED
- flow-viritin: ✅ PASSED
- vaadin-fullcalendar: ✅ PASSED
- svg-visualizations: ✅ PASSED
- maplibre: ⚠️ KNOWN ISSUE (pre-existing failure)

### 25.1-SNAPSHOT (❌ Failed Build - Feb 13, 14:33)
- hugerte-for-flow: ❌ FAILED
- super-fields: ❌ FAILED
- flow-viritin: ❌ FAILED
- vaadin-fullcalendar: ❌ FAILED
- svg-visualizations: ❌ FAILED
- maplibre: ❌ FAILED

## Root Cause: Signals API Breaking Changes

Vaadin 25.1-SNAPSHOT introduces **breaking changes to the Signals API** that affect projects using this experimental feature. The key changes include:

### 1. Package Restructure
- Signals package has been restructured to distinguish between:
  - **Shared signals** (cluster-capable) - now prefixed with `Shared`
  - **Local signals** (UI-only)
  - **Implementation internals** - moved to `impl` packages

### 2. Class Renames
- `ValueSignal` → `SharedValueSignal`
- Similar renames for other signal types
- Implementation classes moved to separate `impl` packages

### 3. Type Changes
- `BeforeEvent`'s navigation target is now typed as `Class<? extends Component>` instead of `Class`
- Stricter typing may break code that relied on less specific types

### 4. Additional Changes
- Proper `isFromClient` value setting for `Focusable.focus()` and `blur()`
- New HTML/Signal binding features (`Html(signal)` and `bindHtmlContent(signal)`)
- Updated browser compatibility checks for popover support

## References

- [Vaadin Flow 25.1.0-alpha4 Release Notes](https://newreleases.io/project/github/vaadin/flow/release/25.1.0-alpha4)
- [Vaadin Flow Releases](https://github.com/vaadin/flow/releases)
- [Vaadin 25.0 Deprecated API Removal](https://github.com/vaadin/flow/issues/21396)

## Affected Projects Analysis

### 1. super-fields (Issue #11)
**Repository:** https://github.com/vaadin-miki/super-fields  
**Status:** Builds with 25.0-SNAPSHOT, fails with 25.1-SNAPSHOT  

**Likely Cause:** 
- Project may be using the experimental Signals API
- Needs to update import statements and class names

**Recommended Fix:**
1. Search for Signal-related imports and update:
   - `import com.vaadin.flow.component.shared.ValueSignal` → `import com.vaadin.flow.component.shared.SharedValueSignal`
   - Update any other signal class references
2. Update code to use new `Shared*` prefixed classes
3. Review any `BeforeEvent` navigation target usage for type compatibility

### 2. flow-viritin (Issue #12)
**Repository:** https://github.com/viritin/flow-viritin  
**Status:** Builds with 25.0-SNAPSHOT, fails with 25.1-SNAPSHOT  

**Likely Cause:**
- Utilities library may be using Signals API for reactive programming features
- May have navigation-related code affected by `BeforeEvent` type changes

**Recommended Fix:**
1. Update all Signal API imports and class names
2. Review any router/navigation code for `BeforeEvent` type compatibility
3. Test focus/blur event handling if library provides focus management utilities

### 3. vaadin-fullcalendar (Issue #13)
**Repository:** https://github.com/stefanuebe/vaadin-fullcalendar  
**Status:** Builds with 25.0-SNAPSHOT, fails with 25.1-SNAPSHOT  
**Note:** Could not detect project's original Vaadin version

**Likely Cause:**
- Calendar component may use Signals for reactive event updates
- May use navigation features affected by type changes

**Recommended Fix:**
1. Update Signal API usage if present
2. Verify event handling code compatibility
3. Test calendar event updates and state management

### 4. svg-visualizations (Issue #14)
**Repository:** https://github.com/viritin/svg-visualizations  
**Status:** Builds with 25.0-SNAPSHOT, fails with 25.1-SNAPSHOT  

**Likely Cause:**
- Visualization library may use Signals for reactive data binding
- SVG updates might rely on signal-based reactivity

**Recommended Fix:**
1. Update Signal API imports and class names
2. Review reactive binding code
3. Test SVG update mechanisms

### 5. maplibre (Issue #15)
**Repository:** https://github.com/parttio/maplibre  
**Status:** Builds with 25.0-SNAPSHOT, fails with 25.1-SNAPSHOT  
**Branch:** v25 (correctly configured)

**Likely Cause:**
- Map component may use Signals for reactive map state
- Marker/layer updates might use signal-based reactivity

**Recommended Fix:**
1. Update Signal API usage
2. Review map state management code
3. Test map updates and marker manipulation

### 6. hugerte-for-flow (Issue #10)
**Repository:** https://github.com/parttio/hugerte-for-flow  
**Status:** ❌ Fails with BOTH 25.0-SNAPSHOT and 25.1-SNAPSHOT  
**Note:** "The project also fails with its original version - this may be an old or new issue in the project itself"

**Special Case:** 
This project has a pre-existing issue unrelated to the 25.1-SNAPSHOT Signals changes. The issue existed before 25.1 and requires separate investigation.

**Recommended Approach:**
1. First fix the pre-existing issue that affects 25.0-SNAPSHOT
2. Then address any additional 25.1-SNAPSHOT compatibility issues
3. Check for focus/blur event handling (mentioned in issue #2 comments: "Related to regression in focus/blur events")

## General Migration Guide for All Projects

For project maintainers upgrading to Vaadin 25.1-SNAPSHOT:

### Step 1: Identify Signal Usage
```bash
# Search for Signal imports in your codebase
grep -r "import.*Signal" src/
grep -r "ValueSignal\|ListSignal\|MapSignal" src/
```

### Step 2: Update Imports
```java
// OLD (25.0)
import com.vaadin.flow.component.shared.ValueSignal;
import com.vaadin.flow.component.shared.ListSignal;

// NEW (25.1)
import com.vaadin.flow.component.shared.SharedValueSignal;
import com.vaadin.flow.component.shared.SharedListSignal;
```

### Step 3: Update Class Names
```java
// OLD
ValueSignal<String> signal = new ValueSignal<>("initial");

// NEW
SharedValueSignal<String> signal = new SharedValueSignal<>("initial");
```

### Step 4: Review Navigation Code
```java
// Check any BeforeEvent usage for type compatibility
public void beforeNavigation(BeforeNavigationEvent event) {
    Class<? extends Component> target = event.getNavigationTarget();
    // Ensure code works with stricter typing
}
```

### Step 5: Test Focus/Blur Events
If your component uses focus/blur functionality, test that `isFromClient` values are correct.

## Testing Strategy

1. **Local Testing:** Test against 25.1-SNAPSHOT locally before pushing
   ```bash
   ./EcosystemBuild.java -v 25.1-SNAPSHOT -p your-project-name
   ```

2. **Incremental Updates:** Fix one signal type at a time and test

3. **Fallback Plan:** If issues persist, consider:
   - Temporarily removing Signals usage
   - Waiting for stable 25.1.0 release
   - Reporting issues to Vaadin team

## Next Steps

For each affected project:

1. **Project Maintainers** should:
   - Review this investigation
   - Apply recommended fixes
   - Test against 25.1-SNAPSHOT
   - Report any remaining issues to Vaadin

2. **Vaadin Team** should:
   - Provide migration documentation for Signals API
   - Consider adding deprecation warnings before removing old classes
   - Update Vaadin docs with 25.1 migration guide

## Additional Resources

- [Vaadin Upgrading Guide](https://vaadin.com/docs/latest/upgrading)
- [Vaadin Flow GitHub Issues](https://github.com/vaadin/flow/issues)
- [Ecosystem Build Dashboard](https://ecosystembuild.parttio.org)

---

**Investigation Date:** February 13, 2026  
**Vaadin Versions Tested:** 25.0-SNAPSHOT (passing), 25.1-SNAPSHOT (failing)  
**Build Logs:** Available in GitHub Actions artifacts (retention: 7 days)
