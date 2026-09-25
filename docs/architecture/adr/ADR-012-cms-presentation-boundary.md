# ADR-012: CMS controls merchandising content; app owns the visual system

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Merchandising must be controllable server-side without letting the backend dictate arbitrary UI styling; taxonomy must not own presentation (CAT-PRESENT-1).

## Decision
CMS/merchandising owns **content**: which categories/banners/shelves appear, their order, visibility, promotional copy, badge text, asset references (CDN URLs) and a **semantic accent name**. The **client owns design tokens** (colors/typography/spacing) and maps a semantic accent name to a local token. CMS never pushes raw hex/CSS.

## Alternatives considered
- CMS pushing raw styling — rejected. Taxonomy owning emoji/tint — rejected (CAT-PRESENT-1).

## Consequences
Clean content/style seam; design can change without a CMS release and vice versa.

## Compatibility / migration implications
None; consumer responses carry no design tokens.
