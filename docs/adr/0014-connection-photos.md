# ADR-0014: Connection photos, cropped from the team member's own screenshots

- Status: Proposed
- Date: 2026-11-10

## Context

People recognise faces faster than names. When a team member pastes a
screenshot of someone's profile or chat into **Log with AI** (ADR-0013), the
person's photo is usually in it. The team asked to keep that picture with the
connection. ADR-0013 keeps nothing from the chat, and ADR-0005 rules out
reading anything from LinkedIn, so keeping a photo needs its own decision.

## Decision

1. Claude also says where the other person's own profile photo is in the
   screenshots: which screenshot, and a box as fractions of its size. It
   leaves this empty for initials, logos, placeholders, or anyone else.
2. The browser crops that box to a square. The person sees the crop, can drag
   a new square over the screenshot, and decides with a checkbox whether to
   save it. It is saved only with the connection, when they press the button.
   For an existing connection that already has a photo, the box starts
   unticked ("Replace their photo").
3. People can also add, change or remove a photo by hand on the connection
   page (the centre square of a JPEG, PNG or WebP they choose).
4. The server decodes every upload and stores a re-encoded JPEG of at most
   256 by 256 pixels in `connection_photo` (V15), so only pixels are kept.
   Uploads over 2 MB, or that are not images, are refused. The photo is
   removed with the connection.
5. Photos are served only to signed-in users, from
   `GET /api/v1/connections/{id}/photo`; links carry the photo's version so
   browsers can cache them. Only pipeline roles change them.
6. Photos belong to connections only. A conversation with someone who is not
   yet a connection (for example, a LinkedIn invitation not yet accepted)
   gets a photo when they are added as a connection.

## Alternatives considered

- **Fetch the photo from the profile URL**: reads LinkedIn, ruled out by
  BR-BD-03 and ADR-0005, and the link expires.
- **Keep the whole screenshot**: stores third-party messages we do not need.
- **Save the crop without showing it**: a wrong box would put someone else's
  face on the record; the preview and the checkbox are cheap.

## Consequences

- The connections list and page show faces, with initials when there is none.
- A face is personal data. Only a small copy is kept, only by a deliberate
  choice, it is visible only inside the app, and it can be removed at any
  time; it goes when the connection goes.
- The database grows by about 10 to 30 KB per photo.
