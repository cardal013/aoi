import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts"
import { buildPlan, nameUuid, normalizeTitle, pickMalManga } from "./manga_list.ts"

Deno.test("IDs iguais aos da app (CloudSyncRulesTest)", () => {
  const source = "2499283573021220255"
  const url = "/manga/one-piece"
  assertEquals(nameUuid(`manga:${source}:${url}`), "7e3f79d9-b25f-3b69-9163-057ee8256090")
  assertEquals(nameUuid(`source:${source}:${url}`), "25c16651-52a7-3b86-b6fa-79f2e90abe68")
  // Texto com mais de 55 bytes (dois blocos do MD5) e não ASCII
  assertEquals(
    nameUuid("manga:1:/series/o-solo-leveling-ragnarok-com-um-titulo-muito-comprido-ção"),
    pythonUuid3,
  )
})

// uuid.UUID(bytes=md5(...), version=3) do Python para o mesmo texto
const pythonUuid3 = "8feec4ed-5094-3983-b85f-7354cca9ac9d"

Deno.test("títulos comparam sem acentos nem pontuação", () => {
  assertEquals(normalizeTitle("Kimetsu no Yaiba: Demon Slayer!"), normalizeTitle("kimetsu no yaiba demon slayer"))
  assertEquals(normalizeTitle("Pokémon"), "pokemon")
})

const node = (id: number, title: string, extra: Record<string, unknown> = {}) => ({ id, title, ...extra })

Deno.test("liga o único de título igual, também pelo inglês, sem novels", () => {
  const nodes = [
    node(1, "Ore dake Level Up na Ken", { alternative_titles: { en: "Solo Leveling" }, media_type: "light_novel" }),
    node(2, "Na Honjaman Level Up", { alternative_titles: { en: "Solo Leveling" }, media_type: "manhwa" }),
    node(3, "Solo Leveling: Ragnarok", { media_type: "manhwa" }),
  ]
  const result = pickMalManga("Solo Leveling (Official)", nodes)
  assertEquals(result.link_state, "LINKED")
  assertEquals(result.mal_id, 2)
})

Deno.test("vários ou nenhum de título igual pede para confirmar", () => {
  const twoMatches = pickMalManga("Monster", [node(1, "Monster"), node(2, "Monster", { alternative_titles: { synonyms: ["x"] } })])
  assertEquals(twoMatches.link_state, "CONFIRM")
  assertEquals(twoMatches.candidates.length, 2)
  assertEquals(pickMalManga("Monster", [node(3, "Monster Musume")]).link_state, "CONFIRM")
  assertEquals(pickMalManga("Monster", []).link_state, "NOT_FOUND")
})

const library = (status: string, chapters: number) => [{ manga_id: "m", source_id: "s", title: "T", status, chapters_read: chapters }]
const link = (synced_status: string | null, synced_chapters: number | null) =>
  new Map([["m", { manga_id: "m", mal_id: 7, link_state: "LINKED", synced_status, synced_chapters }]])
const mal = (status: string, chapters: number) => [{ node: { id: 7, title: "T" }, list_status: { status, num_chapters_read: chapters } }]

Deno.test("primeira vez: ganha o Aoi no estado, fica o maior número de capítulos", () => {
  const [item] = buildPlan(library("COMPLETED", 120), link(null, null), mal("reading", 150))
  assertEquals(item.target, { status: "COMPLETED", chapters: 150 })
  assertEquals(item.set_mal, true)
  assertEquals(item.set_aoi_status, false)
  assertEquals(item.set_aoi_chapters, true)
})

Deno.test("primeira vez sem estar no MAL: acrescenta ao MAL", () => {
  const [item] = buildPlan(library("READING", 10), link(null, null), [])
  assertEquals(item.set_mal, true)
  assertEquals(item.removed_on_mal, false)
})

Deno.test("depois: mudou no MAL passa para o Aoi; mudou nos dois ganha o Aoi", () => {
  const [fromMal] = buildPlan(library("READING", 10), link("READING", 10), mal("dropped", 10))
  assertEquals(fromMal.target.status, "DROPPED")
  assertEquals(fromMal.set_aoi_status, true)
  assertEquals(fromMal.set_mal, false)

  const [both] = buildPlan(library("COMPLETED", 10), link("READING", 10), mal("dropped", 10))
  assertEquals(both.target.status, "COMPLETED")
  assertEquals(both.set_mal, true)
})

Deno.test("capítulos nunca recuam e On Hold é Plan to Read", () => {
  const [item] = buildPlan(library("PLAN_TO_READ", 30), link("PLAN_TO_READ", 30), mal("on_hold", 5))
  assertEquals(item.target, { status: "PLAN_TO_READ", chapters: 30 })
  assertEquals(item.set_aoi_status, false)
  assertEquals(item.set_aoi_chapters, false)
  assertEquals(item.set_mal, true)
})

Deno.test("já sincronizado e tirado do MAL: deixa de sincronizar, não sai do Aoi", () => {
  const [item] = buildPlan(library("READING", 10), link("READING", 10), [])
  assertEquals(item.removed_on_mal, true)
  assertEquals(item.set_mal, false)
  assertEquals(item.set_aoi_status, false)
})
