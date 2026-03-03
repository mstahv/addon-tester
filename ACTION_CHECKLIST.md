# Action Checklist: Updating GitHub Issues

This checklist guides you through posting the investigation findings to the GitHub issues.

## Prerequisites

- [ ] Read SUMMARY.md for quick overview
- [ ] Read INVESTIGATION_25.1-SNAPSHOT.md for detailed analysis
- [ ] Review ISSUE_COMMENTS_TEMPLATE.md for comment templates

## GitHub Issues to Update

### Issue #10: hugerte-for-flow
- **Status:** Special case - fails with both 25.0 and 25.1
- **Template:** See "For Issue #10 (hugerte-for-flow) - Special Case" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Has pre-existing issue, needs two-phase fix
- [ ] Post comment using template
- [ ] Mention pre-existing focus/blur regression
- [ ] Link to investigation report

### Issue #11: super-fields
- **Status:** Passes 25.0, fails 25.1
- **Template:** See "For Issue #11 (super-fields) - Specific" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Likely uses Signals for reactive field updates
- [ ] Post comment using template
- [ ] Highlight Signal API changes
- [ ] Link to migration guide section

### Issue #12: flow-viritin
- **Status:** Passes 25.0, fails 25.1
- **Template:** See "For Issue #12 (flow-viritin) - Specific" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Utilities library may use Signals and router APIs
- [ ] Post comment using template
- [ ] Mention both Signal and BeforeEvent changes
- [ ] Link to investigation report

### Issue #13: vaadin-fullcalendar
- **Status:** Passes 25.0, fails 25.1
- **Template:** See "For Issue #13 (vaadin-fullcalendar) - Specific" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Calendar may use Signals for reactive event updates
- [ ] Post comment using template
- [ ] Suggest reviewing event handling code
- [ ] Link to migration guide

### Issue #14: svg-visualizations
- **Status:** Passes 25.0, fails 25.1
- **Template:** See "For Issue #14 (svg-visualizations) - Specific" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Visualization library likely uses Signals for reactive binding
- [ ] Post comment using template
- [ ] Highlight reactive binding aspects
- [ ] Link to testing strategy

### Issue #15: maplibre
- **Status:** Passes 25.0, fails 25.1
- **Template:** See "For Issue #15 (maplibre) - Specific" in ISSUE_COMMENTS_TEMPLATE.md
- **Key Point:** Map component likely uses Signals for map state
- [ ] Post comment using template
- [ ] Note that branch config (v25) is correct
- [ ] Link to investigation report

## After Posting Comments

### Monitor for Responses
- [ ] Watch for replies from project maintainers
- [ ] Answer any questions about the investigation
- [ ] Provide additional help if needed

### Track Fixes
- [ ] Monitor for PRs in affected projects
- [ ] Re-run ecosystem build against 25.1-SNAPSHOT
- [ ] Close issues as projects are fixed

### Update Documentation
- [ ] Add notes about which projects fixed the issue and how
- [ ] Update INVESTIGATION_GUIDE.md with lessons learned
- [ ] Consider creating a "success stories" section

## Optional: Notify Vaadin Team

Consider creating a summary issue or discussion in Vaadin repositories:

- [ ] Post to [vaadin/flow discussions](https://github.com/vaadin/flow/discussions)
- [ ] Mention the Signals API migration challenge
- [ ] Link to this investigation
- [ ] Request migration documentation

## Template Locations

All templates are in: `ISSUE_COMMENTS_TEMPLATE.md`

Quick access:
```bash
# View all templates
cat ISSUE_COMMENTS_TEMPLATE.md

# Extract specific template (example for issue #11)
sed -n '/## For Issue #11/,/## For Issue #12/p' ISSUE_COMMENTS_TEMPLATE.md
```

## Example Comment Structure

Each comment should include:

1. **Investigation Results header**
2. **Root cause explanation** (Signals API changes)
3. **Recommended fix** (with code examples)
4. **Testing instructions**
5. **Link to full investigation report**

## Automation Ideas (Future)

Consider automating this process:

- [ ] GitHub Actions workflow to auto-comment on issues
- [ ] Script to generate comments from templates
- [ ] Bot to track fix progress across projects

---

**Remember:** The goal is to help project maintainers quickly understand and fix their projects. Be helpful, provide examples, and link to detailed documentation!
