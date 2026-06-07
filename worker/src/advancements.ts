// The progression "frontier". Instead of fragile reads of Minecraft's live
// advancement tree, we hardcode the key advancements into tiers (stone -> iron
// -> diamond -> nether -> end -> bosses). The planner offers something from the
// LOWEST tier the player hasn't cleared, so quests are always progressive and
// never too far ahead. On Hard difficulty we skip straight to the boss tier.
//
// IDs are real vanilla advancement keys so the plugin's PlayerAdvancementDoneEvent
// (which reports e.g. "minecraft:story/smelt_iron") matches active quests exactly.

export interface Adv {
  id: string;
  title: string;
  hint: string;
}

export interface Tier {
  name: string;
  boss?: boolean;
  advs: Adv[];
}

export const TIERS: Tier[] = [
  {
    name: 'Stone Age',
    advs: [
      { id: 'minecraft:story/mine_stone', title: 'Stone Age', hint: 'mine stone with a pickaxe' },
      { id: 'minecraft:story/upgrade_tools', title: 'Getting an Upgrade', hint: 'craft a better pickaxe' },
    ],
  },
  {
    name: 'Iron Age',
    advs: [
      { id: 'minecraft:story/smelt_iron', title: 'Acquire Hardware', hint: 'smelt an iron ingot in a furnace' },
      { id: 'minecraft:story/obtain_armor', title: 'Suit Up', hint: 'forge and wear a piece of iron armor' },
      { id: 'minecraft:story/iron_tools', title: "Isn't It Iron Pick", hint: 'craft an iron pickaxe' },
    ],
  },
  {
    name: 'Deep Delving',
    advs: [
      { id: 'minecraft:story/mine_diamond', title: 'Diamonds!', hint: 'mine diamonds deep underground' },
      { id: 'minecraft:story/enchant_item', title: 'Enchanter', hint: 'enchant an item at an enchanting table' },
      { id: 'minecraft:story/shiny_gear', title: 'Cover Me with Diamonds', hint: 'craft and wear diamond armor' },
    ],
  },
  {
    name: 'The Nether',
    advs: [
      { id: 'minecraft:story/enter_the_nether', title: 'We Need to Go Deeper', hint: 'build a portal and enter the Nether' },
      { id: 'minecraft:nether/find_fortress', title: 'A Terrible Fortress', hint: 'find a nether fortress in the gloom' },
      { id: 'minecraft:nether/obtain_blaze_rod', title: 'Into Fire', hint: 'defeat a blaze and take its rod' },
    ],
  },
  {
    name: 'The End Beckons',
    advs: [
      { id: 'minecraft:story/follow_ender_eye', title: 'Eye Spy', hint: 'follow an eye of ender to a stronghold' },
      { id: 'minecraft:story/enter_the_end', title: 'The End?', hint: 'step through the portal into the End' },
    ],
  },
  {
    name: 'Slayer of Titans',
    boss: true,
    advs: [
      { id: 'minecraft:end/kill_dragon', title: 'Free the End', hint: 'slay the mighty Ender Dragon' },
      { id: 'minecraft:nether/summon_wither', title: 'Withering Heights', hint: 'summon the dreaded Wither' },
    ],
  },
];

export const BOSS_TIER_INDEX = TIERS.findIndex((t) => t.boss);

export interface FrontierPick {
  adv: Adv;
  tier: number;
  tierName: string;
}

/**
 * Pick a progressive quest for a player given the advancements they've completed.
 * Hard difficulty jumps straight to the boss tier (dragon / wither).
 */
export function pickFrontierQuest(completed: Set<string>, difficulty: string): FrontierPick | null {
  if (difficulty === 'hard') {
    const boss = TIERS[BOSS_TIER_INDEX].advs.find((a) => !completed.has(a.id));
    if (boss) return { adv: boss, tier: BOSS_TIER_INDEX, tierName: TIERS[BOSS_TIER_INDEX].name };
  }
  for (let i = 0; i < TIERS.length; i++) {
    if (TIERS[i].boss && difficulty !== 'hard') continue; // never push bosses below Hard
    const adv = TIERS[i].advs.find((a) => !completed.has(a.id));
    if (adv) return { adv, tier: i, tierName: TIERS[i].name };
  }
  return null;
}
