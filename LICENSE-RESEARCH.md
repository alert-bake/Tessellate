# TSL-1.0 Research and Review Notes

**Status:** Internal design notes for the TSL-1.0 working draft. Not legal advice and not part of the license grant.  
**Reviewed draft:** `LICENSE-DRAFT.md`, Version 2.4  
**Research date:** 2026-11-21

## Executive summary

TSL is a proposed custom copyleft license for Tessellate. The current design combines:

- GPL-style permissions and copyleft for distributed covered works;
- an AGPL-inspired source offer for users interacting remotely with modified covered versions;
- contributor patent grants and a patent-litigation termination mechanism influenced by Apache-2.0 and GPL-3.0;
- attribution and project-origin notices;
- separate treatment for trademarks, independent programs, plugins, and third-party components.

This is still a custom draft. It has not been reviewed by a qualified lawyer, approved by the Open Source Initiative (OSI), or tested for compatibility with other licenses.

## Research findings and design choices

### 1. Network use: AGPL-3.0

GNU AGPL-3.0 section 13 requires a modified program that supports remote network interaction to offer all remote users access to the corresponding source, without charge, through a network server and customary copying method.

TSL draft Section 4.6 is modelled on that mechanism. Sections 4.7–4.13 attempt to clarify matching source versions, source access, independent services, and credentials.

Reference:
- GNU AGPL-3.0: https://www.gnu.org/licenses/agpl-3.0.html
- GNU GPL FAQ on remote interaction: https://www.gnu.org/licenses/gpl-faq.html#AGPLv3InteractingRemotely

### 2. Corresponding Source and installation information: GPL-3.0

GPL-3.0 defines Corresponding Source to include the source needed to generate, install, run, and modify the object code, including scripts controlling those activities. It also has a specific Installation Information rule for certain User Products.

TSL Sections 4.3 and 4.14 now attempt to cover those concepts. The exact boundaries, exceptions, and definitions need legal review before publication.

Reference:
- GNU GPL-3.0: https://www.gnu.org/licenses/gpl-3.0.html

### 3. Patent grants and retaliation: Apache-2.0 / GPL-3.0

Apache-2.0 section 3 grants a contributor patent license limited to claims necessarily infringed by that contributor's contribution, alone or in combination with the submitted work. It terminates the patent license for that work if the recipient initiates patent litigation alleging that the work or a contribution infringes a patent.

TSL Section 7.2 adopts a similarly limited contributor-and-licensor patent grant. Section 7.4 uses a patent-litigation termination concept. These clauses are not copied wholesale and may not have the same legal effect as Apache-2.0 or GPL-3.0.

Reference:
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.html
- GNU GPL-3.0: https://www.gnu.org/licenses/gpl-3.0.html

### 4. Open-source status and OSI review

The Open Source Definition requires, among other things, free redistribution, source availability, permission to distribute derived works, and no discrimination against persons or fields of endeavor.

TSL intends to allow commercial use, redistribution, modification, and use in any field. However, it is a custom license and is not OSI-approved. Do not advertise it as OSI-approved or claim formal OSI compliance unless it has been reviewed and approved through the relevant process.

A custom license can also create compatibility problems. Do not assume that TSL-covered code can be combined with GPL- or AGPL-covered code. Compatibility must be assessed against the exact terms and direction of combination.

References:
- Open Source Definition: https://opensource.org/osd
- GNU GPL FAQ: https://www.gnu.org/licenses/gpl-faq.html
- GitHub licensing guidance: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository

## Patent protection: what a license can and cannot do

A license can grant patent permissions that the licensor or contributors actually own or control. It can also make those patent permissions conditional, including termination following specified patent litigation.

A license cannot:
- make Nitrous the inventor of an invention made by someone else;
- transfer patent rights that Nitrous does not own or control;
- prevent every third party from filing a patent application;
- guarantee that a patent office will reject an application;
- invalidate an already-issued patent merely by declaring it invalid in the license.

WIPO explains that software-related patentability varies by jurisdiction and that a software-related invention must satisfy the relevant patentability requirements. WIPO also explains that public disclosure can become prior art and may prevent later patent protection in some jurisdictions. A defensive publication may help prevent later patents on what it adequately discloses, but it does not grant ownership or freedom to operate against existing patents.

References:
- WIPO patent FAQs: https://www.wipo.int/en/web/patents/faq_patents
- WIPO technology-transfer FAQs: https://www.wipo.int/en/web/technology-transfer/faq
- WIPO Patent Drafting Manual: https://www.wipo.int/edocs/pubdocs/en/wipo-pub-867-23-en-wipo-patent-drafting-manual.pdf

**Practical warning:** Do not publicly disclose a potentially patentable invention solely to create defensive prior art until a qualified patent professional has considered whether that disclosure would also destroy an opportunity to seek your own patent.

## Bangladesh-specific legal review

WIPO Lex identifies the following current principal legislation:

- Copyright Act, 2023 (Act No. XXXIV of 2023): https://www.wipo.int/wipolex/en/legislation/details/23028
- Patent Act, 2023 (Act No. LIII of 2023): https://www.wipo.int/wipolex/en/legislation/details/23027
- Trademarks Act, 2009 (Act No. XIX of 2009): listed in Bangladesh's WIPO Lex member profile: https://www.wipo.int/wipolex/en/treaties/members/profile/BD

WIPO Lex notes that the Patent Act, 2023 came into force on 27 February 2025. The Bangladesh Copyright Act, 2023 includes provisions concerning copyright in information-technology-based digital works and first ownership by an author, subject to statutory exceptions and agreements.

These references are starting points, not a legal opinion about how any provision applies to Tessellate, AI-generated code, a particular contribution, or TSL. The authoritative text and current rules should be checked by a Bangladesh-qualified IP lawyer.

## Outstanding issues before any release as TSL-1.0

1. **Covered Work boundary:** Define precisely when a modification, linked component, plugin, mod, or combined distribution is a derivative or covered work. This is legally fact-dependent.
2. **Attribution:** Confirm that the required Nitrous/Tessellate attribution is drafted as a notice-preservation condition and does not become an impermissible advertising or endorsement requirement.
3. **Patent scope:** Confirm the definitions of Necessary Patent Claims, the scope of the grant, the Licensor's own grant, and the patent-litigation termination mechanism.
4. **Termination:** Review the notice, cure, provisional reinstatement, and permanent reinstatement rules for enforceability and consistency.
5. **Third-party components:** Confirm how incompatible dependencies, assets, fonts, and bundled software are handled.
6. **Trademarks:** Decide separately whether to register or otherwise protect the Tessellate name and logo. A software copyright license does not itself reserve a domain name or automatically prevent confusing branding.
7. **Governing law and disputes:** Obtain advice on Bangladesh law and cross-border distribution before selecting governing law, venue, or dispute procedures.
8. **License compatibility:** Obtain a compatibility review before describing TSL as compatible with GPL-3.0, AGPL-3.0, or any other license.
9. **AI-assisted material:** Keep a provenance record of human-authored material, AI-assisted material, third-party material, and contributions. Do not assume every AI-generated line has the same copyright status as human-authored work.

## Recommendation

Keep the current repository file named `LICENSE-DRAFT.md`. Do not create a root `LICENSE` file claiming TSL-1.0 is in force until the terms are reviewed, the rights being licensed are identified, and the final text is approved by the relevant rights holders.

If the priority is a widely recognized open-source license with network copyleft, compare using unmodified AGPL-3.0 plus a separate trademark policy and attribution notices. If the priority is a custom Nitrous-specific license, continue TSL development but expect additional review and possible compatibility costs.

---

This document is research support for the project owner. It is not legal advice and does not create or amend license terms.
