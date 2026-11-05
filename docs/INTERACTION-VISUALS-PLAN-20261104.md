# Interaction visual stage — fixed, new branches only

## Approved scope

App baseline `984196d8c1a6deaff124eaa8dd6c45f55c40e700`; Mini actual branch baseline `55d49b921f541c5c1d793c88e64dfaac51ee32b2` (the initial design review used its earlier `a64c9e5` ancestor).
App branch `codex/app-interaction-visuals-20261104`; Mini branch `codex/miniprogram-interaction-visuals-20261104`.
Preserve card ordering, numeric contracts, navigation, public vehicle art, existing brand loading logo and all persistent data.

| Group | Actual integration | Truth boundary |
| --- | --- | --- |
| Vehicle binding | Official authorization/onboarding header, bundled vehicle illustration | An illustration does not prove a vehicle is bound; only received onboarding state can show confirmation |
| TPMS | Position diagram and selectable wheel focus on existing qualified history | Zero retained; stale/unknown not green/normal; never invent thresholds or sample time |
| A–B | Existing detail endpoint card, selectable endpoint focus | Explicitly a schematic; existing real GPS map unchanged |
| Tesla/WeChat | Existing authorization/account consumers | No new login/session/permission operations; binding distinct from Tesla grant |
| Parking | Actual verified charging-session rail and recorded boundary labels | Missing end stays missing; gaps never prove no charging |
| Openings | Optional expansion of existing opening alert | Aggregate doors/windows do not reveal a specific door; unknown stays unknown |
| Empty/loading | Existing drive/charge empty cards and branded loader | Error distinct from no records; offscreen/reduced-motion stops visual animation |

Vehicle photos use the pre-existing packaged genuine vehicle image catalogue. The neutral top view is a position schematic matched across clients, not a manufactured exact model/trim photo. Text, pressure, time, warnings and connection results are native live elements, never baked into an image.

No schema/version/session keys are changed. No vehicle command, provider request, production operation, installation or source-branch overwrite is authorized. Local Owner worktrees are untouched. Native Mini rendering and real provider/device acceptance remain distinct from synthetic tests.
