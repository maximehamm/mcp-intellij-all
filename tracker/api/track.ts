import { neon } from '@neondatabase/serverless';
import type { VercelRequest, VercelResponse } from '@vercel/node';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).end();

  const { client_id, tool_name, plugin_version } = req.body ?? {};

  if (
    typeof client_id !== 'string' || client_id.length > 100 ||
    typeof tool_name !== 'string' || tool_name.length > 100
  ) {
    return res.status(400).json({ error: 'invalid payload' });
  }

  const version = typeof plugin_version === 'string' ? plugin_version.slice(0, 20) : 'unknown';
  const sql = neon(process.env.POSTGRES_URL!);

  await sql`
    INSERT INTO events (client_id, tool_name, plugin_version, created_at)
    VALUES (${client_id}, ${tool_name}, ${version}, NOW())
  `;

  return res.status(200).json({ ok: true });
}
