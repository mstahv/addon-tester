# How to Use Investigation Reports

When build failures occur against new Vaadin versions, investigation reports are created to help project maintainers understand and fix the issues.

## For Project Maintainers

If your project is listed in a build failure issue:

1. **Read the Investigation Report**
   - Look for investigation reports named `INVESTIGATION_<version>.md`
   - Example: `INVESTIGATION_25.1-SNAPSHOT.md`

2. **Find Your Project Section**
   - Each report includes project-specific analysis
   - Includes likely causes and recommended fixes

3. **Use the Issue Comment Templates**
   - `ISSUE_COMMENTS_TEMPLATE.md` contains ready-to-use comment text
   - These explain the issue to your project's users

4. **Apply the Fixes**
   - Follow the migration steps in the investigation report
   - Test against the new Vaadin version
   - Report back any issues or success

## For Ecosystem Build Maintainers

When creating investigation reports:

1. **Compare Build Results**
   - Compare passing vs failing builds
   - Identify which projects changed status
   - Note timing patterns (fast vs slow failures)

2. **Research Breaking Changes**
   - Check Vaadin release notes
   - Review GitHub issues and pull requests
   - Search for API changes

3. **Document Findings**
   - Create investigation report in `INVESTIGATION_<version>.md`
   - Include:
     - Summary of failures
     - Root cause analysis
     - Project-specific recommendations
     - General migration guide
     - References and resources

4. **Create Comment Templates**
   - Create templates in `ISSUE_COMMENTS_TEMPLATE.md`
   - Provide both general and project-specific versions
   - Include code examples and testing commands

5. **Update GitHub Issues**
   - Post investigation findings as comments
   - Link to the full investigation report
   - Provide actionable next steps

## Investigation Report Template

```markdown
# Investigation: Build Failures Against Vaadin X.Y.Z

## Summary
[High-level overview of failures]

## Background
[Comparison of test results across versions]

## Root Cause
[Detailed analysis of what changed]

## Affected Projects Analysis
[Per-project breakdown with recommendations]

## General Migration Guide
[Step-by-step instructions for all affected projects]

## Testing Strategy
[How to test fixes]

## Next Steps
[What maintainers should do]

## Additional Resources
[Links to relevant documentation]
```

## Example Investigation

See `INVESTIGATION_25.1-SNAPSHOT.md` for a complete example of:
- Comparing build results across versions
- Identifying Signals API breaking changes
- Providing project-specific recommendations
- Creating a comprehensive migration guide

## Example Issue Comments

See `ISSUE_COMMENTS_TEMPLATE.md` for examples of:
- General comments for all issues
- Project-specific comments
- Special case handling (pre-existing failures)
