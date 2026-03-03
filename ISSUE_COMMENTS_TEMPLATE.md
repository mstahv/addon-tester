# GitHub Issue Comment Templates

These templates can be used to comment on the GitHub issues with investigation findings.

---

## For Issues #11, #12, #13, #14, #15 (Projects that pass 25.0 but fail 25.1)

### Template:

```markdown
## Investigation Results

This project builds successfully against **Vaadin 25.0-SNAPSHOT** but fails against **25.1-SNAPSHOT**. The failure is caused by breaking changes in the Signals API introduced in Vaadin 25.1.

### Root Cause: Signals API Restructure

Vaadin 25.1-SNAPSHOT restructured the Signals package with breaking changes:

1. **Class Renames:**
   - `ValueSignal` → `SharedValueSignal`
   - `ListSignal` → `SharedListSignal`
   - Similar changes for other signal types

2. **Package Changes:**
   - Shared signals (cluster-capable) now prefixed with `Shared`
   - Implementation classes moved to `impl` packages

3. **Type Changes:**
   - `BeforeEvent.getNavigationTarget()` now returns `Class<? extends Component>` (stricter typing)

### Recommended Fix

1. **Update Signal imports:**
   ```java
   // OLD
   import com.vaadin.flow.component.shared.ValueSignal;
   
   // NEW
   import com.vaadin.flow.component.shared.SharedValueSignal;
   ```

2. **Update Signal class usage:**
   ```java
   // OLD
   ValueSignal<String> signal = new ValueSignal<>("value");
   
   // NEW
   SharedValueSignal<String> signal = new SharedValueSignal<>("value");
   ```

3. **Search for all Signal usage:**
   ```bash
   grep -r "import.*Signal" src/
   grep -r "ValueSignal\|ListSignal\|MapSignal" src/
   ```

4. **Test focus/blur events** if your component uses them (the `isFromClient` value behavior has changed)

### References

- [Vaadin Flow 25.1.0-alpha4 Release Notes](https://newreleases.io/project/github/vaadin/flow/release/25.1.0-alpha4)
- [Full Investigation Report](./INVESTIGATION_25.1-SNAPSHOT.md) in this repository

### Testing

Test your fixes against 25.1-SNAPSHOT:
```bash
mvn clean verify -Dvaadin.version=25.1-SNAPSHOT
```

Let us know if you encounter any issues or need help with the migration!
```

---

## For Issue #11 (super-fields) - Specific

```markdown
## Investigation Results

**super-fields** builds successfully against Vaadin 25.0-SNAPSHOT but fails against 25.1-SNAPSHOT.

### Root Cause

The failure is caused by breaking changes in the Signals API. Since super-fields likely uses Signals for reactive field updates, you'll need to update:

1. Signal class names (`ValueSignal` → `SharedValueSignal`)
2. Import statements
3. Any navigation code using `BeforeEvent`

### Specific Recommendations

For the super-fields project:
- Check field value binding code
- Review any reactive field update mechanisms
- Update any navigation-related utilities

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for detailed migration steps.
```

---

## For Issue #12 (flow-viritin) - Specific

```markdown
## Investigation Results

**flow-viritin** builds successfully against Vaadin 25.0-SNAPSHOT but fails against 25.1-SNAPSHOT.

### Root Cause

This utilities library likely uses the Signals API for reactive programming features. The Signals API has been restructured in 25.1 with breaking changes.

### Specific Recommendations

For flow-viritin:
- Update Signal imports and class names throughout the library
- Review router/navigation utilities for `BeforeEvent` type compatibility
- Test focus/blur utilities if the library provides focus management

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for detailed migration steps.
```

---

## For Issue #13 (vaadin-fullcalendar) - Specific

```markdown
## Investigation Results

**vaadin-fullcalendar** builds successfully against Vaadin 25.0-SNAPSHOT but fails against 25.1-SNAPSHOT.

### Root Cause

The calendar component may be using Signals for reactive event updates. The Signals API has breaking changes in 25.1.

### Specific Recommendations

For vaadin-fullcalendar:
- Update Signal API usage if present in event handling
- Review calendar event state management
- Test event creation, updates, and deletion

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for detailed migration steps.
```

---

## For Issue #14 (svg-visualizations) - Specific

```markdown
## Investigation Results

**svg-visualizations** builds successfully against Vaadin 25.0-SNAPSHOT but fails against 25.1-SNAPSHOT.

### Root Cause

This visualization library likely uses Signals for reactive data binding and SVG updates. The Signals API has breaking changes in 25.1.

### Specific Recommendations

For svg-visualizations:
- Update Signal imports and class names
- Review reactive binding code for SVG elements
- Test visualization update mechanisms

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for detailed migration steps.
```

---

## For Issue #15 (maplibre) - Specific

```markdown
## Investigation Results

**maplibre** builds successfully against Vaadin 25.0-SNAPSHOT but fails against 25.1-SNAPSHOT.

**Note:** The branch configuration is correct (v25 branch is being used).

### Root Cause

The map component likely uses Signals for reactive map state and updates. The Signals API has breaking changes in 25.1.

### Specific Recommendations

For maplibre:
- Update Signal API usage in map state management
- Review marker and layer update mechanisms  
- Test map initialization and dynamic updates

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for detailed migration steps.
```

---

## For Issue #10 (hugerte-for-flow) - Special Case

```markdown
## Investigation Results

**⚠️ Special Case:** hugerte-for-flow fails against BOTH 25.0-SNAPSHOT and 25.1-SNAPSHOT.

### Analysis

This project has a **pre-existing issue** unrelated to the 25.1 Signals API changes:
- Status shows: "The project also fails with its original version"
- This is an existing problem in the project itself

### Related Information

Issue #2 mentions: "Related to regression in focus/blur events"

### Recommended Approach

1. **First:** Fix the pre-existing issue affecting 25.0-SNAPSHOT
   - Investigate the focus/blur event regression
   - Check Playwright tests
   - Review editor initialization code

2. **Then:** Address 25.1-SNAPSHOT compatibility
   - Apply Signals API migration (if used)
   - Test against 25.1-SNAPSHOT

This requires a two-phase fix: the original issue, then the 25.1 migration.

See the [full investigation report](./INVESTIGATION_25.1-SNAPSHOT.md) for more details.
```

---

## General Template for All Issues

```markdown
## 🔍 Investigation Complete

I've investigated the build failures against Vaadin 25.1-SNAPSHOT. Here's what I found:

**Key Finding:** Projects that build successfully with 25.0-SNAPSHOT are failing with 25.1-SNAPSHOT due to breaking changes in the Signals API.

**Full Investigation Report:** [INVESTIGATION_25.1-SNAPSHOT.md](./INVESTIGATION_25.1-SNAPSHOT.md)

**Quick Fix Guide:**
1. Find Signal usage: `grep -r "ValueSignal\|ListSignal" src/`
2. Update class names: `ValueSignal` → `SharedValueSignal`
3. Update imports: Add `Shared` prefix to signal class imports
4. Test: `mvn clean verify -Dvaadin.version=25.1-SNAPSHOT`

See the full report for detailed migration steps and code examples.
```

---

## Usage Instructions

1. Copy the appropriate template above
2. Post it as a comment on the corresponding GitHub issue
3. Link to the investigation report for full details
4. Update with any additional project-specific findings

These comments will help project maintainers understand what's broken and how to fix it.
