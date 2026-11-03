import type { ControlPlaneDb } from '@lms/db/control-plane';

/** Gieo domain đã xác minh cho một fixture không gian và cấp issuer cố định để guard đi đúng resolver. */
export async function seedVerifiedSpaceDomain(
  db: ControlPlaneDb,
  spaceId: string,
  domain: string,
): Promise<void> {
  await db
    .updateTable('space_registry')
    .set({ issuer_url: `https://issuer.test.local/realms/${domain}` })
    .where('id', '=', spaceId)
    .execute();
  await db
    .insertInto('space_domains')
    .values({
      space_id: spaceId,
      domain,
      kind: 'primary',
      status: 'verified',
      verified_at: new Date(),
    })
    .execute();
}
