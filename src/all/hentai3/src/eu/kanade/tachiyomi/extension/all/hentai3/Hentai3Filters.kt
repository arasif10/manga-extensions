package eu.kanade.tachiyomi.extension.all.hentai3

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * The sort comes first and the write-in boxes come last, so the long tag lists stay out of the way
 * of the boxes that are ticked most often.
 */
fun getFilters(lang: String): FilterList {
    val filters = mutableListOf<Filter<*>>(
        SelectFilter("Sort by", getSortsList),
        Filter.Separator(),
        CategoryFilter(),
    )

    // A source pinned to one language already searches in that language, so offering the language
    // list there would let the two contradict each other and answer with nothing.
    if (lang == "all") filters += LanguageFilter()

    filters += FemaleTagsFilter()
    filters += MaleTagsFilter()
    filters += OtherTagsFilter()
    filters += Filter.Separator()
    filters += AdvancedFilterGroup()

    return FilterList(*filters.toTypedArray())
}

/** One tickable option: ticked to search for it, ticked twice to keep it out. */
internal class TriStateTag(name: String, val value: String) : Filter.TriState(name)

/**
 * A group of tags as tick boxes. The site matches tag names itself, so the values are written the
 * way it spells them - including the `(female)` / `(male)` suffix that places a name in a category.
 *
 * A ticked option becomes a search clause and a ticked-off one becomes the same clause with a
 * leading dash, which is how the site keeps a tag out without any other box being ticked.
 */
internal abstract class TagGroupFilter(
    name: String,
    private val category: String,
    options: List<Pair<String, String>>,
) : Filter.Group<TriStateTag>(name, options.map { TriStateTag(it.first, it.second) }) {

    val clauses: List<String> get() = state.mapNotNull { option ->
        when {
            option.isIncluded() -> clause(option.value, exclude = false)
            option.isExcluded() -> clause(option.value, exclude = true)
            else -> null
        }
    }

    /** `tags:'big breasts (female)'`, or `-language:'chinese'` when the option is ticked off. */
    private fun clause(value: String, exclude: Boolean) = buildString {
        if (exclude) append('-')
        append(category).append(":'").append(value).append('\'')
    }
}

/**
 * The categories the site carries. Only the ones that answer with results are offered, so ticking
 * one never leads to an empty listing.
 */
internal class CategoryFilter :
    TagGroupFilter(
        "Category",
        "category",
        listOf(
            "Doujinshi" to "doujinshi",
            "Manga" to "manga",
            "Western" to "western",
            "Non-H" to "non-h",
            "Misc" to "misc",
        ),
    )

/** The languages the site tags its galleries with. */
internal class LanguageFilter :
    TagGroupFilter(
        "Language",
        "language",
        listOf(
            "English" to "english",
            "Japanese" to "japanese",
            "Chinese" to "chinese",
            "Korean" to "korean",
            "Spanish" to "spanish",
            "French" to "french",
            "Portuguese" to "portuguese",
            "Italian" to "italian",
            "Russian" to "russian",
            "Thai" to "thai",
            "Vietnamese" to "vietnamese",
            "Indonesian" to "indonesian",
            "German" to "german",
            "Polish" to "polish",
        ),
    )

/** Every tag the site files under the female category, A to Z. */
internal class FemaleTagsFilter :
    TagGroupFilter(
        "Tags (female)",
        "tags",
        tagOptions(FEMALE_TAGS, "female"),
    )

/** Every tag the site files under the male category, A to Z. */
internal class MaleTagsFilter :
    TagGroupFilter(
        "Tags (male)",
        "tags",
        tagOptions(MALE_TAGS, "male"),
    )

/** Every remaining tag the site carries, which belongs to no gender category. */
internal class OtherTagsFilter :
    TagGroupFilter(
        "Tags (other)",
        "tags",
        tagOptions(OTHER_TAGS),
    )

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a tag that the long
 * lists above do not carry, a series, a character, an artist, a group or a language can be written
 * down instead of hunted for, and a leading dash keeps one out.
 */
internal class AdvancedFilterGroup :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf(
            Filter.Header("Write names that are not listed above, separated by commas (,)."),
            Filter.Header("Prepend a name with a dash (-) to keep it out."),
            TextFilter("Tags", "tags"),
            TextFilter("Male Tags", "tags", "male"),
            TextFilter("Female Tags", "tags", "female"),
            TextFilter("Series", "series"),
            TextFilter("Characters", "characters"),
            TextFilter("Artists", "artist"),
            TextFilter("Groups", "groups"),
            TextFilter("Languages", "language"),
            Filter.Header("Filter by pages, for example: >20"),
            TextFilter("Pages", "page"),
        ),
    )

/**
 * A write-in box. Every name written into it becomes a search clause of its own category, so
 * `big breasts, ahegao` in the female box searches for both female tags.
 */
internal open class TextFilter(
    name: String,
    private val type: String,
    private val categorySuffix: String = "",
) : Filter.Text(name) {

    /** The page count is written the way the site accepts it, everything else as tag names. */
    private val isPageCount = type == "page"

    val clauses: List<String> get() = state.split(',', ';', '\n').mapNotNull { token ->
        val written = token.trim()
        if (written.isEmpty()) return@mapNotNull null

        val exclude = written.startsWith('-')
        val name = written.removePrefix("-").lowercase().trim()
        if (name.isEmpty()) return@mapNotNull null

        if (isPageCount) {
            "$type:$name"
        } else {
            buildString {
                if (exclude) append('-')
                append(type).append(":'").append(name).append(suffixOf(name)).append('\'')
            }
        }
    }

    /** The site picks the category from a `(male)` / `(female)` suffix, added only if absent. */
    private fun suffixOf(name: String) = when {
        categorySuffix.isEmpty() -> ""
        name.endsWith(" (male)") || name.endsWith(" (female)") -> ""
        else -> " ($categorySuffix)"
    }
}

internal open class SelectFilter(
    name: String,
    private val vals: List<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
    fun getValue() = vals[state].second
}

private val getSortsList: List<Pair<String, String>> = listOf(
    Pair("Recent", ""),
    Pair("Popular: All Time", "popular"),
    Pair("Popular: Week", "popular-7d"),
    Pair("Popular: Today", "popular-24h"),
)

/**
 * Every tag the site carries, taken from its own tag index and kept in the order the filter shows
 * it: one `Label|name as the site spells it` per line, sorted A to Z.
 *
 * [suffix] is the category the site files these names under, which it reads off the name itself, so
 * it is appended here rather than repeated on every line.
 */
private fun tagOptions(data: String, suffix: String = ""): List<Pair<String, String>> = data.trimIndent().lines().map(String::trim).filter(String::isNotEmpty).map { line ->
    val (label, name) = line.split('|', limit = 2)
    label to if (suffix.isEmpty()) name else "$name ($suffix)"
}

private val FEMALE_TAGS =
    """
        Absorption|absorption
        Additional Eyes|additional eyes
        Adventitious Mouth|adventitious mouth
        Adventitious Penis|adventitious penis
        Afro|afro
        Age Progression|age progression
        Age Regression|age regression
        Ahegao|ahegao
        Albino|albino
        Alien|alien
        Amputee|amputee
        Anal|anal
        Anal Intercourse|anal intercourse
        Anal Plug|anal plug
        Angel|angel
        Animal On Animal|animal on animal
        Animal On Furry|animal on furry
        Animegao|animegao
        Anorexic|anorexic
        Apparel Bukkake|apparel bukkake
        Apron|apron
        Armpit Licking|armpit licking
        Armpit Sex|armpit sex
        Asphyxiation|asphyxiation
        Ass Expansion|ass expansion
        Assjob|assjob
        Autofellatio|autofellatio
        Bald|bald
        Ball Caressing|ball caressing
        Ball Sucking|ball sucking
        Balljob|balljob
        Balls Expansion|balls expansion
        Bandages|bandages
        Bandaid|bandaid
        BBW|bbw
        BDSM|bdsm
        Bear|bear
        Beauty Mark|beauty mark
        Bestiality|bestiality
        Big Areolae|big areolae
        Big Ass|big ass
        Big Balls|big balls
        Big Breasts|big breasts
        Big Lips|big lips
        Big Muscles|big muscles
        Big Nipples|big nipples
        Big Penis|big penis
        Bike Shorts|bike shorts
        Bikini|bikini
        Bisexual|bisexual
        Bite Mark|bite mark
        Blackmail|blackmail
        Blind|blind
        Blindfold|blindfold
        Blood|blood
        Bloomers|bloomers
        Blowjob|blowjob
        Blowjob Face|blowjob face
        Body Modification|body modification
        Body Painting|body painting
        Body Swap|body swap
        Body Writing|body writing
        Bodystocking|bodystocking
        Bodysuit|bodysuit
        Bondage|bondage
        Braces|braces
        Brain Fuck|brain fuck
        Breast Expansion|breast expansion
        Breast Feeding|breast feeding
        Bride|bride
        Bukkake|bukkake
        Business Suit|business suit
        Butler|butler
        Butt Plug|butt plug
        Cashier|cashier
        Cat|cat
        Cbt|cbt
        Centaur|centaur
        Chastity Belt|chastity belt
        Cheating|cheating
        Cheerleader|cheerleader
        Chikan|chikan
        Chinese Dress|chinese dress
        Chloroform|chloroform
        Christmas|christmas
        Clit Insertion|clit insertion
        Clit Stimulation|clit stimulation
        Clone|clone
        Closed Eyes|closed eyes
        Clown|clown
        Coach|coach
        Cock Ring|cock ring
        Cockslapping|cockslapping
        Collar|collar
        Condom|condom
        Confinement|confinement
        Corruption|corruption
        Corset|corset
        Cosplaying|cosplaying
        Cousin|cousin
        Crossdressing|crossdressing
        Crotch Tattoo|crotch tattoo
        Crown|crown
        Crying|crying
        Cum Bath|cum bath
        Cumflation|cumflation
        Cunnilingus|cunnilingus
        Dark Nipples|dark nipples
        Dark Sclera|dark sclera
        Dark Skin|dark skin
        Deepthroat|deepthroat
        Deer|deer
        Denki Anma|denki anma
        Detached Sleeves|detached sleeves
        Diaper|diaper
        Dick Growth|dick growth
        Dicknipples|dicknipples
        Dinosaur|dinosaur
        Dog|dog
        Doll Joints|doll joints
        Dolphin|dolphin
        Domination Loss|domination loss
        Donkey|donkey
        Double Anal|double anal
        Double Blowjob|double blowjob
        Double Penetration|double penetration
        Dougi|dougi
        Dragon|dragon
        Drill Hair|drill hair
        Drugs|drugs
        Drunk|drunk
        Eel|eel
        Eggs|eggs
        Electric Shocks|electric shocks
        Elf|elf
        Emotionless Sex|emotionless sex
        Enema|enema
        Exhibitionism|exhibitionism
        Exposed Clothing|exposed clothing
        Eye-Covering Bang|eye-covering bang
        Eyemask|eyemask
        Eyepatch|eyepatch
        Facesitting|facesitting
        Facial Hair|facial hair
        Fairy|fairy
        Fanny Packing|fanny packing
        Farting|farting
        Filming|filming
        First Person Perspective|first person perspective
        Fish|fish
        Fishnets|fishnets
        Fisting|fisting
        Focus Anal|focus anal
        Focus Blowjob|focus blowjob
        Food On Body|food on body
        Foot Licking|foot licking
        Footjob|footjob
        Forced Exposure|forced exposure
        Fox|fox
        Freckles|freckles
        Frog|frog
        Frottage|frottage
        Fundoshi|fundoshi
        Furry|furry
        Futanari|futanari
        Gag|gag
        Gang Rape|gang rape
        Garter Belt|garter belt
        Gasmask|gasmask
        Gender Bender|gender bender
        Gender Change|gender change
        Gender Morph|gender morph
        Genital Piercing|genital piercing
        Ghost|ghost
        Giant Sperm|giant sperm
        Gijinka|gijinka
        Glasses|glasses
        Glory Hole|glory hole
        Gloves|gloves
        Goat|goat
        Goblin|goblin
        Gokkun|gokkun
        Gothic Lolita|gothic lolita
        Group|group
        Growth|growth
        Gymshorts|gymshorts
        Hair Buns|hair buns
        Hairjob|hairjob
        Hairy|hairy
        Hairy Armpits|hairy armpits
        Halo|halo
        Handicapped|handicapped
        Handjob|handjob
        Harem|harem
        Harness|harness
        Harpy|harpy
        Headphones|headphones
        Heterochromia|heterochromia
        Hidden Sex|hidden sex
        Hidden Toy|hidden toy
        High Heels|high heels
        Hood|hood
        Horns|horns
        Horse|horse
        Horse Cock|horse cock
        Hotpants|hotpants
        Huge Breasts|huge breasts
        Huge Penis|huge penis
        Human Cattle|human cattle
        Human On Furry|human on furry
        Human Pet|human pet
        Humiliation|humiliation
        Impregnation|impregnation
        Incest|incest
        Infantilism|infantilism
        Insect|insect
        Inseki|inseki
        Inverted Nipples|inverted nipples
        Invisible|invisible
        Kangaroo|kangaroo
        Kappa|kappa
        Kemonomimi|kemonomimi
        Kigurumi Pajama|kigurumi pajama
        Kimono|kimono
        Kindergarten Uniform|kindergarten uniform
        Kissing|kissing
        Knotted Penis|knotted penis
        Kunoichi|kunoichi
        Lab Coat|lab coat
        Lactation|lactation
        Large Insertions|large insertions
        Large Tattoo|large tattoo
        Latex|latex
        Layer Cake|layer cake
        Leash|leash
        Leg Lock|leg lock
        Leotard|leotard
        Lingerie|lingerie
        Lipstick Mark|lipstick mark
        Long Tongue|long tongue
        Low Bestiality|low bestiality
        Low Incest|low incest
        Low Smegma|low smegma
        Machine|machine
        Magical Girl|magical girl
        Maid|maid
        Makeup|makeup
        Masked Face|masked face
        Masturbation|masturbation
        Mesugaki|mesugaki
        Mesuiki|mesuiki
        Metal Armor|metal armor
        Midget|midget
        Miko|miko
        Military|military
        Milking|milking
        Mind Break|mind break
        Mind Control|mind control
        Monkey|monkey
        Monoeye|monoeye
        Monster|monster
        Monster Girl|monster girl
        Moral Degeneration|moral degeneration
        Mouse|mouse
        Mouth Mask|mouth mask
        Multimouth Blowjob|multimouth blowjob
        Multiple Arms|multiple arms
        Multiple Assjob|multiple assjob
        Multiple Handjob|multiple handjob
        Multiple Orgasms|multiple orgasms
        Multiple Pairings|multiple pairings
        Multiple Penises|multiple penises
        Multiple Straddling|multiple straddling
        Multiple Tails|multiple tails
        Muscle|muscle
        Muscle Growth|muscle growth
        Mute|mute
        Nakadashi|nakadashi
        Navel Fuck|navel fuck
        Netorare|netorare
        Netorase|netorase
        Nipple Piercing|nipple piercing
        Nipple Stimulation|nipple stimulation
        Nose Hook|nose hook
        Nun|nun
        Nurse|nurse
        Octopus|octopus
        Oil|oil
        Onahole|onahole
        Oni|oni
        Orc|orc
        Orgasm Denial|orgasm denial
        Oyakodon|oyakodon
        Painted Nails|painted nails
        Paizuri|paizuri
        Panther|panther
        Pantyhose|pantyhose
        Pantyjob|pantyjob
        Parasite|parasite
        Pasties|pasties
        Pegasus|pegasus
        Penis Bumps|penis bumps
        Penis Enlargement|penis enlargement
        Penis Reduction|penis reduction
        Petplay|petplay
        Petrification|petrification
        Phimosis|phimosis
        Phone Sex|phone sex
        Piercing|piercing
        Pig|pig
        Pillory|pillory
        Pirate|pirate
        Piss Drinking|piss drinking
        Pixie Cut|pixie cut
        Pole Dancing|pole dancing
        Ponytail|ponytail
        Possession|possession
        Pregnant|pregnant
        Prehensile Hair|prehensile hair
        Prolapse|prolapse
        Property Tag|property tag
        Prostate Massage|prostate massage
        Prostitution|prostitution
        Pubic Stubble|pubic stubble
        Public Use|public use
        Rabbit|rabbit
        Randoseru|randoseru
        Rape|rape
        Reptile|reptile
        Retractable Penis|retractable penis
        Rimjob|rimjob
        Robot|robot
        Ruined Orgasm|ruined orgasm
        Ryona|ryona
        Saliva|saliva
        Sarashi|sarashi
        Scar|scar
        School Gym Uniform|school gym uniform
        School Swimsuit|school swimsuit
        Schoolboy Uniform|schoolboy uniform
        Schoolgirl Uniform|schoolgirl uniform
        Scrotal Lingerie|scrotal lingerie
        Selfcest|selfcest
        Sex Toys|sex toys
        Shared Senses|shared senses
        Shark|shark
        Shaved Head|shaved head
        Sheep|sheep
        Shemale|shemale
        Shibari|shibari
        Shimaidon|shimaidon
        Shimapan|shimapan
        Shrinking|shrinking
        Skinsuit|skinsuit
        Slave|slave
        Sleeping|sleeping
        Slime|slime
        Slug|slug
        Small Penis|small penis
        Smalldom|smalldom
        Smegma|smegma
        Smell|smell
        Smoking|smoking
        Snake|snake
        Snuff|snuff
        Sockjob|sockjob
        Solo Action|solo action
        Spanking|spanking
        Speculum|speculum
        Spider|spider
        Stewardess|stewardess
        Stirrup Legwear|stirrup legwear
        Stockings|stockings
        Stomach Deformation|stomach deformation
        Straitjacket|straitjacket
        Strap-On|strap-on
        Stretching|stretching
        Stuck In Wall|stuck in wall
        Sumata|sumata
        Sundress|sundress
        Sunglasses|sunglasses
        Sweating|sweating
        Swimsuit|swimsuit
        Swinging|swinging
        Syringe|syringe
        Tabi Socks|tabi socks
        Table Masturbation|table masturbation
        Tail|tail
        Tail Plug|tail plug
        Tailjob|tailjob
        Tanlines|tanlines
        Teacher|teacher
        Tentacles|tentacles
        Thick Eyebrows|thick eyebrows
        Thigh High Boots|thigh high boots
        Tiara|tiara
        Tickling|tickling
        Tiger|tiger
        Tights|tights
        Tooth Brushing|tooth brushing
        Torture|torture
        Tracksuit|tracksuit
        Trampling|trampling
        Transformation|transformation
        Transparent Clothing|transparent clothing
        Triple Anal|triple anal
        Triple Penetration|triple penetration
        Tube|tube
        Turtle|turtle
        Tutor|tutor
        Twins|twins
        Twintails|twintails
        Unbirth|unbirth
        Underwater|underwater
        Underwater Sex|underwater sex
        Unicorn|unicorn
        Unusual Pupils|unusual pupils
        Unusual Teeth|unusual teeth
        Urethra Insertion|urethra insertion
        Urination|urination
        Vacbed|vacbed
        Vampire|vampire
        Very Long Hair|very long hair
        Voyeurism|voyeurism
        Vtuber|vtuber
        Waiter|waiter
        Waitress|waitress
        Weight Gain|weight gain
        Wet Clothes|wet clothes
        Whip|whip
        Wings|wings
        Witch|witch
        Wolf|wolf
        Wooden Horse|wooden horse
        Worm|worm
        Wormhole|wormhole
        Wrestling|wrestling
        X-Ray|x-ray
        Yandere|yandere
        Zebra|zebra
        Zombie|zombie
    """

private val MALE_TAGS =
    """
        Absorption|absorption
        Additional Eyes|additional eyes
        Adventitious Mouth|adventitious mouth
        Adventitious Penis|adventitious penis
        Afro|afro
        Age Progression|age progression
        Age Regression|age regression
        Ahegao|ahegao
        Albino|albino
        Alien|alien
        Amputee|amputee
        Anal|anal
        Anal Intercourse|anal intercourse
        Anal Plug|anal plug
        Angel|angel
        Animal On Animal|animal on animal
        Animal On Furry|animal on furry
        Animegao|animegao
        Anorexic|anorexic
        Apparel Bukkake|apparel bukkake
        Apron|apron
        Armpit Licking|armpit licking
        Armpit Sex|armpit sex
        Asphyxiation|asphyxiation
        Ass Expansion|ass expansion
        Assjob|assjob
        Autofellatio|autofellatio
        Bald|bald
        Ball Caressing|ball caressing
        Ball Sucking|ball sucking
        Balljob|balljob
        Balls Expansion|balls expansion
        Bandages|bandages
        Bandaid|bandaid
        BBW|bbw
        BDSM|bdsm
        Bear|bear
        Beauty Mark|beauty mark
        Bestiality|bestiality
        Big Areolae|big areolae
        Big Ass|big ass
        Big Balls|big balls
        Big Breasts|big breasts
        Big Lips|big lips
        Big Muscles|big muscles
        Big Nipples|big nipples
        Big Penis|big penis
        Bike Shorts|bike shorts
        Bikini|bikini
        Bisexual|bisexual
        Bite Mark|bite mark
        Blackmail|blackmail
        Blind|blind
        Blindfold|blindfold
        Blood|blood
        Bloomers|bloomers
        Blowjob|blowjob
        Blowjob Face|blowjob face
        Body Modification|body modification
        Body Painting|body painting
        Body Swap|body swap
        Body Writing|body writing
        Bodystocking|bodystocking
        Bodysuit|bodysuit
        Bondage|bondage
        Braces|braces
        Brain Fuck|brain fuck
        Breast Expansion|breast expansion
        Breast Feeding|breast feeding
        Bride|bride
        Bukkake|bukkake
        Business Suit|business suit
        Butler|butler
        Butt Plug|butt plug
        Cashier|cashier
        Cat|cat
        Cbt|cbt
        Centaur|centaur
        Chastity Belt|chastity belt
        Cheating|cheating
        Cheerleader|cheerleader
        Chikan|chikan
        Chinese Dress|chinese dress
        Chloroform|chloroform
        Christmas|christmas
        Clit Insertion|clit insertion
        Clit Stimulation|clit stimulation
        Clone|clone
        Closed Eyes|closed eyes
        Clown|clown
        Coach|coach
        Cock Ring|cock ring
        Cockslapping|cockslapping
        Collar|collar
        Condom|condom
        Confinement|confinement
        Corruption|corruption
        Corset|corset
        Cosplaying|cosplaying
        Cousin|cousin
        Crossdressing|crossdressing
        Crotch Tattoo|crotch tattoo
        Crown|crown
        Crying|crying
        Cum Bath|cum bath
        Cumflation|cumflation
        Cunnilingus|cunnilingus
        Dark Nipples|dark nipples
        Dark Sclera|dark sclera
        Dark Skin|dark skin
        Deepthroat|deepthroat
        Deer|deer
        Denki Anma|denki anma
        Detached Sleeves|detached sleeves
        Diaper|diaper
        Dick Growth|dick growth
        Dicknipples|dicknipples
        Dinosaur|dinosaur
        Dog|dog
        Doll Joints|doll joints
        Dolphin|dolphin
        Domination Loss|domination loss
        Donkey|donkey
        Double Anal|double anal
        Double Blowjob|double blowjob
        Double Penetration|double penetration
        Dougi|dougi
        Dragon|dragon
        Drill Hair|drill hair
        Drugs|drugs
        Drunk|drunk
        Eel|eel
        Eggs|eggs
        Electric Shocks|electric shocks
        Elf|elf
        Emotionless Sex|emotionless sex
        Enema|enema
        Exhibitionism|exhibitionism
        Exposed Clothing|exposed clothing
        Eye-Covering Bang|eye-covering bang
        Eyemask|eyemask
        Eyepatch|eyepatch
        Facesitting|facesitting
        Facial Hair|facial hair
        Fairy|fairy
        Fanny Packing|fanny packing
        Farting|farting
        Filming|filming
        First Person Perspective|first person perspective
        Fish|fish
        Fishnets|fishnets
        Fisting|fisting
        Focus Anal|focus anal
        Focus Blowjob|focus blowjob
        Food On Body|food on body
        Foot Licking|foot licking
        Footjob|footjob
        Forced Exposure|forced exposure
        Fox|fox
        Freckles|freckles
        Frog|frog
        Frottage|frottage
        Fundoshi|fundoshi
        Furry|furry
        Futanari|futanari
        Gag|gag
        Gang Rape|gang rape
        Garter Belt|garter belt
        Gasmask|gasmask
        Gender Bender|gender bender
        Gender Change|gender change
        Gender Morph|gender morph
        Genital Piercing|genital piercing
        Ghost|ghost
        Giant Sperm|giant sperm
        Gijinka|gijinka
        Glasses|glasses
        Glory Hole|glory hole
        Gloves|gloves
        Goat|goat
        Goblin|goblin
        Gokkun|gokkun
        Gothic Lolita|gothic lolita
        Group|group
        Growth|growth
        Gymshorts|gymshorts
        Hair Buns|hair buns
        Hairjob|hairjob
        Hairy|hairy
        Hairy Armpits|hairy armpits
        Halo|halo
        Handicapped|handicapped
        Handjob|handjob
        Harem|harem
        Harness|harness
        Harpy|harpy
        Headphones|headphones
        Heterochromia|heterochromia
        Hidden Sex|hidden sex
        Hidden Toy|hidden toy
        High Heels|high heels
        Hood|hood
        Horns|horns
        Horse|horse
        Horse Cock|horse cock
        Hotpants|hotpants
        Huge Breasts|huge breasts
        Huge Penis|huge penis
        Human Cattle|human cattle
        Human On Furry|human on furry
        Human Pet|human pet
        Humiliation|humiliation
        Impregnation|impregnation
        Incest|incest
        Infantilism|infantilism
        Insect|insect
        Inseki|inseki
        Inverted Nipples|inverted nipples
        Invisible|invisible
        Kangaroo|kangaroo
        Kappa|kappa
        Kemonomimi|kemonomimi
        Kigurumi Pajama|kigurumi pajama
        Kimono|kimono
        Kindergarten Uniform|kindergarten uniform
        Kissing|kissing
        Knotted Penis|knotted penis
        Kunoichi|kunoichi
        Lab Coat|lab coat
        Lactation|lactation
        Large Insertions|large insertions
        Large Tattoo|large tattoo
        Latex|latex
        Layer Cake|layer cake
        Leash|leash
        Leg Lock|leg lock
        Leotard|leotard
        Lingerie|lingerie
        Lipstick Mark|lipstick mark
        Long Tongue|long tongue
        Low Bestiality|low bestiality
        Low Incest|low incest
        Low Smegma|low smegma
        Machine|machine
        Magical Girl|magical girl
        Maid|maid
        Makeup|makeup
        Masked Face|masked face
        Masturbation|masturbation
        Mesugaki|mesugaki
        Mesuiki|mesuiki
        Metal Armor|metal armor
        Midget|midget
        Miko|miko
        Military|military
        Milking|milking
        Mind Break|mind break
        Mind Control|mind control
        Monkey|monkey
        Monoeye|monoeye
        Monster|monster
        Monster Girl|monster girl
        Moral Degeneration|moral degeneration
        Mouse|mouse
        Mouth Mask|mouth mask
        Multimouth Blowjob|multimouth blowjob
        Multiple Arms|multiple arms
        Multiple Assjob|multiple assjob
        Multiple Handjob|multiple handjob
        Multiple Orgasms|multiple orgasms
        Multiple Penises|multiple penises
        Multiple Straddling|multiple straddling
        Multiple Tails|multiple tails
        Muscle|muscle
        Muscle Growth|muscle growth
        Mute|mute
        Nakadashi|nakadashi
        Navel Fuck|navel fuck
        Netorare|netorare
        Netorase|netorase
        Nipple Piercing|nipple piercing
        Nipple Stimulation|nipple stimulation
        Nose Hook|nose hook
        Nun|nun
        Nurse|nurse
        Octopus|octopus
        Oil|oil
        Onahole|onahole
        Oni|oni
        Orc|orc
        Orgasm Denial|orgasm denial
        Oyakodon|oyakodon
        Painted Nails|painted nails
        Paizuri|paizuri
        Panther|panther
        Pantyhose|pantyhose
        Pantyjob|pantyjob
        Parasite|parasite
        Pasties|pasties
        Pegasus|pegasus
        Penis Bumps|penis bumps
        Penis Enlargement|penis enlargement
        Penis Reduction|penis reduction
        Petplay|petplay
        Petrification|petrification
        Phimosis|phimosis
        Phone Sex|phone sex
        Piercing|piercing
        Pig|pig
        Pillory|pillory
        Pirate|pirate
        Piss Drinking|piss drinking
        Pixie Cut|pixie cut
        Pole Dancing|pole dancing
        Ponytail|ponytail
        Possession|possession
        Pregnant|pregnant
        Prehensile Hair|prehensile hair
        Prolapse|prolapse
        Property Tag|property tag
        Prostate Massage|prostate massage
        Prostitution|prostitution
        Pubic Stubble|pubic stubble
        Public Use|public use
        Rabbit|rabbit
        Randoseru|randoseru
        Rape|rape
        Reptile|reptile
        Retractable Penis|retractable penis
        Rimjob|rimjob
        Robot|robot
        Ruined Orgasm|ruined orgasm
        Ryona|ryona
        Saliva|saliva
        Sarashi|sarashi
        Scar|scar
        School Gym Uniform|school gym uniform
        School Swimsuit|school swimsuit
        Schoolboy Uniform|schoolboy uniform
        Schoolgirl Uniform|schoolgirl uniform
        Scrotal Lingerie|scrotal lingerie
        Selfcest|selfcest
        Sex Toys|sex toys
        Shared Senses|shared senses
        Shark|shark
        Shaved Head|shaved head
        Sheep|sheep
        Shemale|shemale
        Shibari|shibari
        Shimaidon|shimaidon
        Shimapan|shimapan
        Shrinking|shrinking
        Skinsuit|skinsuit
        Slave|slave
        Sleeping|sleeping
        Slime|slime
        Slug|slug
        Small Penis|small penis
        Smalldom|smalldom
        Smegma|smegma
        Smell|smell
        Smoking|smoking
        Snake|snake
        Snuff|snuff
        Sockjob|sockjob
        Solo Action|solo action
        Spanking|spanking
        Speculum|speculum
        Spider|spider
        Stewardess|stewardess
        Stirrup Legwear|stirrup legwear
        Stockings|stockings
        Stomach Deformation|stomach deformation
        Straitjacket|straitjacket
        Strap-On|strap-on
        Stretching|stretching
        Stuck In Wall|stuck in wall
        Sumata|sumata
        Sundress|sundress
        Sunglasses|sunglasses
        Sweating|sweating
        Swimsuit|swimsuit
        Swinging|swinging
        Syringe|syringe
        Tabi Socks|tabi socks
        Table Masturbation|table masturbation
        Tail|tail
        Tail Plug|tail plug
        Tailjob|tailjob
        Tanlines|tanlines
        Teacher|teacher
        Tentacles|tentacles
        Thick Eyebrows|thick eyebrows
        Thigh High Boots|thigh high boots
        Tiara|tiara
        Tickling|tickling
        Tiger|tiger
        Tights|tights
        Tooth Brushing|tooth brushing
        Torture|torture
        Tracksuit|tracksuit
        Trampling|trampling
        Transformation|transformation
        Transparent Clothing|transparent clothing
        Triple Anal|triple anal
        Triple Penetration|triple penetration
        Tube|tube
        Turtle|turtle
        Tutor|tutor
        Twins|twins
        Twintails|twintails
        Unbirth|unbirth
        Underwater|underwater
        Underwater Sex|underwater sex
        Unicorn|unicorn
        Unusual Pupils|unusual pupils
        Unusual Teeth|unusual teeth
        Urethra Insertion|urethra insertion
        Urination|urination
        Vacbed|vacbed
        Vampire|vampire
        Very Long Hair|very long hair
        Voyeurism|voyeurism
        Vtuber|vtuber
        Waiter|waiter
        Waitress|waitress
        Weight Gain|weight gain
        Wet Clothes|wet clothes
        Whip|whip
        Wings|wings
        Witch|witch
        Wolf|wolf
        Wooden Horse|wooden horse
        Worm|worm
        Wormhole|wormhole
        Wrestling|wrestling
        X-Ray|x-ray
        Yandere|yandere
        Zebra|zebra
        Zombie|zombie
    """

private val OTHER_TAGS =
    """
        35 Machi|35 machi
        3D|3d
        5505 Project|5505 project
        7 No Oden Wa 70 Yen|7 no oden wa 70 yen
        96panda|96panda
        Aburitoro Salmon O Kawari|aburitoro salmon o kawari
        Adventitious Vagina|adventitious vagina
        Agero|agero
        Aizawa Anji|aizawa anji
        Aizawa Seinikuten|aizawa seinikuten
        Akaimelon|akaimelon
        Akuyaku Warai Sandankatsuyou|akuyaku warai sandankatsuyou
        Alien Girl|alien girl
        All The Way Through|all the way through
        Almirua|almirua
        Already Uploaded|already uploaded
        Amamoli Furiko|amamoli furiko
        Anaglyph|anaglyph
        Anal Prolapse|anal prolapse
        Animal On Animal|animal on animal
        Animated|animated
        Anthology|anthology
        Aoto Kage|aoto kage
        Arisu|arisu
        Artbook|artbook
        Artistcg|artistcg
        Arubento|arubento
        Atelier Bucha|atelier bucha
        Aunt|aunt
        Autopaizuri|autopaizuri
        Ayase Mio|ayase mio
        Ayatsuki|ayatsuki
        Ayazou|ayazou
        Baa|baa
        Ball-Less Shemale|ball-less shemale
        Barghest|barghest
        Bat|bat
        Bat Boy|bat boy
        Bat Girl|bat girl
        Bathing Room|bathing room
        BBM|bbm
        Beach|beach
        Bear Boy|bear boy
        Bear Girl|bear girl
        Beastmania|beastmania
        Bebebebebe|bebebebebe
        Bee Boy|bee boy
        Bee Girl|bee girl
        Big Clit|big clit
        Big Vagina|big vagina
        Bio Booster Armor Guyver|bio booster armor guyver
        Bird Boy|bird boy
        Bird Girl|bird girl
        Birth|birth
        Black Lilith|black lilith
        Blowjob|blowjob
        Body Swap|body swap
        Bonbi|bonbi
        Breast Reduction|breast reduction
        Brother|brother
        Bull|bull
        Bunny Boy|bunny boy
        Bunny Girl|bunny girl
        Burping|burping
        Buta Hormone|buta hormone
        Buta Minchi|buta minchi
        Butaharu|butaharu
        Butaniku|butaniku
        Byakurou|byakurou
        Canchira Canpany|canchira canpany
        Canvas 2|canvas 2
        Canyne Khai|canyne khai
        Capsule|capsule
        Caption|caption
        Carny|carny
        Catboy|catboy
        Catfight|catfight
        Catgirl|catgirl
        Cervix Penetration|cervix penetration
        Chaos Angels|chaos angels
        Chappie.|chappie.
        Chase H.q.|chase h.q.
        Cheese Pan|cheese pan
        Chenori Yubune|chenori yubune
        Cherry Pop|cherry pop
        Chichinoya|chichinoya
        Chie Hori|chie hori
        Chika Madoka|chika madoka
        Chinese Animation-School Shock|chinese animation-school shock
        Chinkamoya|chinkamoya
        Chino|chino
        Chinpan|chinpan
        Chitsu Kara Liver|chitsu kara liver
        Chiyoda Ao|chiyoda ao
        Chiyomatsu|chiyomatsu
        Chloe Wichers|chloe wichers
        Cho-Ryu|cho-ryu
        Chobikuma|chobikuma
        Chode|chode
        Chohan|chohan
        Choiki|choiki
        Chomuchomu Kai|chomuchomu kai
        Choukushin Kurage|choukushin kurage
        Chuchu|chuchu
        Chuu|chuu
        Chuuka Denenken|chuuka denenken
        Circle Mattsao|circle mattsao
        Circle Ni|circle ni
        Clairvoyance|clairvoyance
        Clamp|clamp
        Classroom|classroom
        Clef|clef
        Clit Growth|clit growth
        Clo|clo
        Cloaca Insertion|cloaca insertion
        Clockant|clockant
        Clothed Female Nude Male|clothed female nude male
        Clothed Male Nude Female|clothed male nude female
        Clothed Paizuri|clothed paizuri
        Clumcykiss8|clumcykiss8
        Cobura No Oyatsu|cobura no oyatsu
        Coleus|coleus
        Comic|comic
        Compilation|compilation
        Conejologia|conejologia
        Conjoined|conjoined
        Coprophagia|coprophagia
        Corpse|corpse
        Cotoco|cotoco
        Cotolet|cotolet
        Cow|cow
        Cowgirl|cowgirl
        Cowman|cowman
        Cum In Eye|cum in eye
        Cum Swap|cum swap
        Cuntboy|cuntboy
        Cuntbusting|cuntbusting
        Cure Whip|cure whip
        Dabi|dabi
        Dadada|dadada
        Dadamore|dadamore
        Dadoujinya|dadoujinya
        Daigorou|daigorou
        Daji|daji
        Dakimakura|dakimakura
        Dakko Ja Rrs 2nd|dakko ja rrs 2nd
        Dan Oniroku|dan oniroku
        Daraku|daraku
        Darnic Prestone Yggdmillennia|darnic prestone yggdmillennia
        Daughter|daughter
        Deaf|deaf
        Deer Boy|deer boy
        Deer Girl|deer girl
        Defaced|defaced
        Defloration|defloration
        Dekairuka|dekairuka
        Demon|demon
        Demon Girl|demon girl
        Deserted Island|deserted island
        Devilman Lady|devilman lady
        Dhibi Shoten|dhibi shoten
        Diamond|diamond
        Dickgirl On Dickgirl|dickgirl on dickgirl
        Dickgirl On Female|dickgirl on female
        Dickgirl On Male|dickgirl on male
        Dickgirls Only|dickgirls only
        Digital Works|digital works
        DILF|dilf
        Dinasty Warriors|dinasty warriors
        Dismantling|dismantling
        Dna|dna
        Doboshiru|doboshiru
        Dog Boy|dog boy
        Dog Girl|dog girl
        Dogaya|dogaya
        Doggie-Yu|doggie-yu
        Doku Doku Kinoko|doku doku kinoko
        Doku Ninjin|doku ninjin
        Donguri-Sensei|donguri-sensei
        Donko|donko
        Donuna|donuna
        Dosukoikyuutarou|dosukoikyuutarou
        Double Vaginal|double vaginal
        Doujinharuga|doujinharuga
        Doujinshi|doujinshi
        Draenei|draenei
        Dragon Beast|dragon beast
        Draugnut|draugnut
        Drawg|drawg
        Dreamy Kikaku|dreamy kikaku
        Duga|duga
        Dummy Kaiko|dummy kaiko
        Duran|duran
        E Bifurai Akitan|e bifurai akitan
        Eagleheart|eagleheart
        Ebio|ebio
        Ebizori Tengoku|ebizori tengoku
        Ehrrr|ehrrr
        Eien|eien
        Eigatsu Nine|eigatsu nine
        Eihikanshi|eihikanshi
        Eisbein|eisbein
        Eki|eki
        El115|el115
        Elephant|elephant
        En-Ten|en-ten
        Enako|enako
        Enari|enari
        Erokaida|erokaida
        Erolum|erolum
        Erosheee|erosheee
        Eruu|eruu
        Esnmyu|esnmyu
        Etou Yukiko|etou yukiko
        Evening Breeze|evening breeze
        Ewokaku No Minn|ewokaku no minn
        Exchange|exchange
        Exhibitionism|exhibitionism
        Extraneous Ads|extraneous ads
        Fantom Rose 1|fantom rose 1
        Fantom Rose 2|fantom rose 2
        Father|father
        Faulklin|faulklin
        Fauxfur|fauxfur
        Females Only|females only
        Femdom|femdom
        Feminization|feminization
        FFF Threesome|fff threesome
        FFM Threesome|ffm threesome
        Fft Threesome|fft threesome
        Fiberr|fiberr
        Fingering|fingering
        Fliming|fliming
        Focus Handjob|focus handjob
        Focus Paizuri|focus paizuri
        Focus Rimjob|focus rimjob
        Fonteynart|fonteynart
        Fool107|fool107
        Foot Insertion|foot insertion
        Forniphilia|forniphilia
        Fox Boy|fox boy
        Fox Girl|fox girl
        Freak Studio|freak studio
        Freeze|freeze
        Freia Kagami|freia kagami
        Frog Boy|frog boy
        Frog Girl|frog girl
        Frottage|frottage
        Fujii Kasai|fujii kasai
        Fujii Tooru|fujii tooru
        Fujimoto Go|fujimoto go
        Fujinomiya Yu|fujinomiya yu
        Fujisaki Mana|fujisaki mana
        Fujishima Kousuke Fx|fujishima kousuke fx
        Fujiwarake|fujiwarake
        Fujutsushi|fujutsushi
        Fukkin|fukkin
        Full Body Tattoo|full body tattoo
        Full Censored|full censored
        Full Censorship|full censorship
        Full Color|full color
        Full-Packaged Futanari|full-packaged futanari
        Fumiman|fumiman
        Furigana|furigana
        Furikake.|furikake.
        Futafuta Kouku|futafuta kouku
        Futanari Ochinchin Land|futanari ochinchin land
        Futanarization|futanarization
        Futsuka|futsuka
        Fuuka Kazaguruma|fuuka kazaguruma
        Fuuzen No Tomoshibi|fuuzen no tomoshibi
        Fuwatoro Lion|fuwatoro lion
        Gachirin|gachirin
        Gakuen Saimin Reido|gakuen saimin reido
        Gamanuntaka|gamanuntaka
        Game Manual|game manual
        Gamecg|gamecg
        Ganchan|ganchan
        Gang Rape|gang rape
        Gaping|gaping
        Gatekeeper|gatekeeper
        Genkai|genkai
        Genkai Sokai Sukekomashi|genkai sokai sukekomashi
        Genkaiten|genkaiten
        Genkurou|genkurou
        Gerbera|gerbera
        Getchu|getchu
        Getter Robo Go|getter robo go
        Gevanni|gevanni
        Giant|giant
        Giantess|giantess
        Gigantic Breasts|gigantic breasts
        Gindara|gindara
        Ginga No Shippo|ginga no shippo
        Ginkaku|ginkaku
        Ginmaru|ginmaru
        Giraffe Girl|giraffe girl
        Giulio|giulio
        Gli|gli
        Gogaek|gogaek
        Gogogo|gogogo
        Gokan|gokan
        Goki Rakugan|goki rakugan
        Goko|goko
        Gokuraku Syogun|gokuraku syogun
        Gokusaishiki Matenrou|gokusaishiki matenrou
        Golden Bazooka|golden bazooka
        Gomuta|gomuta
        Gorgar|gorgar
        Gorilla|gorilla
        Gorocha|gorocha
        Gorou|gorou
        Goudoushi|goudoushi
        Gouryu|gouryu
        Granddaughter|granddaughter
        Grandfather|grandfather
        Grandmother|grandmother
        Great Chocolate|great chocolate
        Grouo|grouo
        Group|group
        Gsk|gsk
        Gumiko|gumiko
        Gun-En|gun-en
        Gunma|gunma
        Guntz|guntz
        Gunyou Mikan|gunyou mikan
        Guttari|guttari
        Guuerosu|guuerosu
        Guusuka|guusuka
        Gya|gya
        Gyaru|gyaru
        Gyaru-Oh|gyaru-oh
        Gyupaibyu|gyupaibyu
        Hacka Doll No.0|hacka doll no.0
        Haigure|haigure
        Haitenai|haitenai
        Hakkin-San|hakkin-san
        Hako No Naka No Imaginary|hako no naka no imaginary
        Hakudaku|hakudaku
        Hamaguri|hamaguri
        Hamamii|hamamii
        Hamu|hamu
        Hana Bunny|hana bunny
        Hanakaidou|hanakaidou
        Hanakaidou Yukio|hanakaidou yukio
        Hanaranman|hanaranman
        Hanetsuki Hina|hanetsuki hina
        Hanraotoko|hanraotoko
        Hapi Sani|hapi sani
        Hara Chimu|hara chimu
        Hardcore|hardcore
        Hareta|hareta
        Haruaki Riko|haruaki riko
        Haruba|haruba
        Haruchan|haruchan
        Haruhiko Inasuda|haruhiko inasuda
        Harumi To|harumi to
        Harunaya|harunaya
        Hasegawa Rainy|hasegawa rainy
        Hatomame Coffee|hatomame coffee
        Hawknuf|hawknuf
        Hayama Yomogi|hayama yomogi
        Hayase Hidekazu|hayase hidekazu
        Hayashi Curry|hayashi curry
        Hazama Shin|hazama shin
        Headless|headless
        Hedgehog Boy|hedgehog boy
        Hedgehog Girl|hedgehog girl
        Heine|heine
        Henachoko Domei|henachoko domei
        Higashi|higashi
        Higehurai|higehurai
        Hiiraki|hiiraki
        Hijab|hijab
        Himishiro|himishiro
        Himura Hiroki|himura hiroki
        Hinareya|hinareya
        Hinyari|hinyari
        Hironikuru Senga Anarogu|hironikuru senga anarogu
        Hiropon|hiropon
        Hiropons|hiropons
        Hitozuma Kasumi-San|hitozuma kasumi-san
        Hitsuji Daisuki|hitsuji daisuki
        Hitsuka|hitsuka
        Hituka|hituka
        Hiyoko-Nabe|hiyoko-nabe
        Hiyori|hiyori
        Hokkyoku Kumaneko|hokkyoku kumaneko
        Homo Noumin|homo noumin
        Honda Akito|honda akito
        Honno Shiromi|honno shiromi
        Hori Makoto|hori makoto
        Horse Boy|horse boy
        Horse Girl|horse girl
        Hoshi No Ji|hoshi no ji
        Hoshigaki Seizoujo|hoshigaki seizoujo
        Hoshilily|hoshilily
        Hoshino Ichiyoru|hoshino ichiyoru
        Hosikawa Amano|hosikawa amano
        Hosshiwa|hosshiwa
        How To|how to
        Hrpk|hrpk
        Huka|huka
        Hyena Boy|hyena boy
        Hyena Girl|hyena girl
        Hyo-Ketsu Crown|hyo-ketsu crown
        Hys|hys
        Hyura|hyura
        Ice Sakuramochi|ice sakuramochi
        Ichi-Gsm|ichi-gsm
        Ichika Ichiko|ichika ichiko
        Ichimedouhonpo|ichimedouhonpo
        Ichimoji Rasetsu|ichimoji rasetsu
        Ichinensei Ni Nacchattara|ichinensei ni nacchattara
        Ichinose Yuma|ichinose yuma
        Ichiru|ichiru
        Ichiru Nozomu|ichiru nozomu
        Icing|icing
        Ignorance|ignorance
        Iiniku Ushijima|iiniku ushijima
        Iisuke|iisuke
        Ikenai|ikenai
        Ikkasei Zenkenbou|ikkasei zenkenbou
        Imageset|imageset
        Imai|imai
        Immorality|immorality
        Imomaru.|imomaru.
        Imomochi|imomochi
        Inazumasaru|inazumasaru
        Incest|incest
        Incomplete|incomplete
        Infirmary|infirmary
        Inflation|inflation
        Inoki Risu|inoki risu
        Inoue|inoue
        Insect Boy|insect boy
        Insect Girl|insect girl
        Inseki|inseki
        Internal Urination|internal urination
        Inui Achu|inui achu
        Inunoomawarisama|inunoomawarisama
        Isabel O Sullivan|isabel o sullivan
        Ishimari Yuuya|ishimari yuuya
        Ishizu|ishizu
        Isshiki Fuji|isshiki fuji
        Itotiisakihito|itotiisakihito
        Itou Mine|itou mine
        Iyo No Kama|iyo no kama
        Iyoudon|iyoudon
        Izukichi|izukichi
        J-Plum|j-plum
        Jagd Pkag|jagd pkag
        Jaggi|jaggi
        Jaguchi|jaguchi
        Jamta|jamta
        Jeck|jeck
        Jiizeru Engine|jiizeru engine
        Jikken B-Tou|jikken b-tou
        Jin Hiroka|jin hiroka
        Jingi|jingi
        Jinsei Mainichi Ga Sacrificeke|jinsei mainichi ga sacrificeke
        Jojo|jojo
        Jojojozosho|jojojozosho
        Josou Seme|josou seme
        Jou Edogawa|jou edogawa
        Juewang Yuyi|juewang yuyi
        Jukan-Ya|jukan-ya
        Julio|julio
        Junkie Daijin|junkie daijin
        Juzo Honenuki|juzo honenuki
        K.z.z.|k.z.z.
        K.z.z. Force|k.z.z. force
        K.z.z. Gundan|k.z.z. gundan
        Kaede Kunikida|kaede kunikida
        Kagaku Chop|kagaku chop
        Kagami Ryou|kagami ryou
        Kahkitimeyear|kahkitimeyear
        Kaicho|kaicho
        Kairaku|kairaku
        Kaiten Kussaku Kikou|kaiten kussaku kikou
        Kaitou Nyanko|kaitou nyanko
        Kaitouchuu|kaitouchuu
        Kakei Yamato|kakei yamato
        Kakisaki|kakisaki
        Kaleido Horoscope|kaleido horoscope
        Kamatsukatei|kamatsukatei
        Kame-Hame Hanten|kame-hame hanten
        Kamenoashi|kamenoashi
        Kamiduki Shuu|kamiduki shuu
        Kaminobe|kaminobe
        Kaminobe Kanon|kaminobe kanon
        Kamome|kamome
        Kanatofu|kanatofu
        Kanbe Rino|kanbe rino
        Kancho|kancho
        Kangaroo Boy|kangaroo boy
        Kangaroo Girl|kangaroo girl
        Kani Tomato|kani tomato
        Kankitsu Mitsu|kankitsu mitsu
        Kanna Akizuki|kanna akizuki
        Karakai Jouzu No Moto Takagi-San|karakai jouzu no moto takagi-san
        Karasuza|karasuza
        Kare-Nidaikon|kare-nidaikon
        Karino Sugata|karino sugata
        Karino Teru|karino teru
        Karuna|karuna
        Kasahara Tsuyoshi|kasahara tsuyoshi
        Kawada|kawada
        Keifuto|keifuto
        Keri|keri
        Kibidango Yun|kibidango yun
        Kien-Biu|kien-biu
        Kikui Maishi|kikui maishi
        Kimetsutenshi. L|kimetsutenshi. l
        Kimika|kimika
        Kimmie|kimmie
        Kina Kotatu|kina kotatu
        Kinkaku|kinkaku
        Kinniku Riron|kinniku riron
        Kinokino|kinokino
        Kirakou|kirakou
        Kirara Fantasia|kirara fantasia
        Kireji|kireji
        Kiri-Tansu.|kiri-tansu.
        Kirimiya|kirimiya
        Kiryuu Oboro|kiryuu oboro
        Kisaragi Rey|kisaragi rey
        Kisei Toukyou|kisei toukyou
        Kishimoto Saisi|kishimoto saisi
        Kitaichi Naco|kitaichi naco
        Kitamatsuya|kitamatsuya
        Kitchen|kitchen
        Kitsune No Tebukuro|kitsune no tebukuro
        Kiyokawa Tsuneaki|kiyokawa tsuneaki
        Kiyomasa|kiyomasa
        Kizaki Baltan|kizaki baltan
        Kmp|kmp
        Kneepit Sex|kneepit sex
        Kni|kni
        Ko9konnan|ko9konnan
        Kobu Ramen Man|kobu ramen man
        Kodomo Doushi|kodomo doushi
        Kodomo Only|kodomo only
        Kohako|kohako
        Kokima Dai|kokima dai
        Kokomi|kokomi
        Kokushi|kokushi
        Komaruya|komaruya
        Kome Juice|kome juice
        Konoha Genpatsu|konoha genpatsu
        Korokoro Dou|korokoro dou
        Koromochi|koromochi
        Koshian|koshian
        Kosian|kosian
        Kosuke Poke|kosuke poke
        Kotatsuneko|kotatsuneko
        Kotegawa Yui No Doujin O Kaku Hito|kotegawa yui no doujin o kaku hito
        Kotohogiya|kotohogiya
        Kotosui|kotosui
        Kou-Chan|kou-chan
        Koube Iori|koube iori
        Kouboku|kouboku
        Kouno Binshiho|kouno binshiho
        Koutestu|koutestu
        Kozakura Nagiha|kozakura nagiha
        Kozo Youhei|kozo youhei
        Kozountoko|kozountoko
        Ksk Suru Shoudou|ksk suru shoudou
        Kubikari Spoon|kubikari spoon
        Kudamonoichizu|kudamonoichizu
        Kudou Maimu|kudou maimu
        Kujo Shima|kujo shima
        Kuma Shounen|kuma shounen
        Kuon Makoto|kuon makoto
        Kuon Michiyosh|kuon michiyosh
        Kurazushi|kurazushi
        Kurita Suzume|kurita suzume
        Kurokabo|kurokabo
        Kurokawa Luck|kurokawa luck
        Kurokawa Rei|kurokawa rei
        Kuromame Mume|kuromame mume
        Kuruno|kuruno
        Kururi Active|kururi active
        Kuryu Josai|kuryu josai
        Kusamakura Tabito|kusamakura tabito
        Kusanagi|kusanagi
        Kushibiki Keita|kushibiki keita
        Kusuhara Nao|kusuhara nao
        Kusunoki Asato|kusunoki asato
        Kuusou Luminous Box|kuusou luminous box
        Kuzuaki|kuzuaki
        Kuzuha Pote Gitsune|kuzuha pote gitsune
        Kyarameru Inu|kyarameru inu
        Kyun Ja|kyun ja
        Lacatation|lacatation
        Lagrange Point|lagrange point
        Lander|lander
        Last Crime|last crime
        Latin-Kei Hige Kyoudai|latin-kei hige kyoudai
        Latin-Kei Hige Oyaji|latin-kei hige oyaji
        Lazy Blue|lazy blue
        Lee Soo-Hyon|lee soo-hyon
        Legjob|legjob
        Lemon Umiushi|lemon umiushi
        Letoard|letoard
        Lilium Plan|lilium plan
        Lillian Ljungstrom|lillian ljungstrom
        Lin Lin|lin lin
        Lion|lion
        Lioness|lioness
        Little Note|little note
        Liu Moon Eater|liu moon eater
        Living Clothes|living clothes
        Lizard Girl|lizard girl
        Lizard Guy|lizard guy
        Lizzie|lizzie
        Lodoss-Tou Senki|lodoss-tou senki
        Lois|lois
        Lolicon|lolicon
        Loon Koubou|loon koubou
        Lora|lora
        Low Incest|low incest
        Low Lolicon|low lolicon
        Low Shotacon|low shotacon
        Lucky Cat|lucky cat
        Lustful Berry|lustful berry
        M.f.h.h.|m.f.h.h.
        Machida March|machida march
        Macross Attack Team|macross attack team
        Madao|madao
        Maggot|maggot
        Magunoro|magunoro
        Mahiru No Ashiato|mahiru no ashiato
        Majikikku|majikikku
        Major Arcana 14|major arcana 14
        Makara|makara
        Makie Sazaki|makie sazaki
        Makorone|makorone
        Makusu|makusu
        Male On Dickgirl|male on dickgirl
        Males Only|males only
        Mama No Nioi|mama no nioi
        Maman|maman
        Mame Hikouki|mame hikouki
        Manga Teikoku|manga teikoku
        Mangourt|mangourt
        Maou Kyuu|maou kyuu
        Marcello|marcello
        Marimoya|marimoya
        Marushivu|marushivu
        Masani Sadakichi|masani sadakichi
        Masaru|masaru
        Masaya Kouichi|masaya kouichi
        Massao|massao
        Masturbtion|masturbtion
        Masuta|masuta
        Mata Kara Stream|mata kara stream
        Matenzakura Miki|matenzakura miki
        Matsumoto|matsumoto
        Matsumoto Hikaru|matsumoto hikaru
        Matsumoto Noda|matsumoto noda
        Mattarisita Hibi|mattarisita hibi
        Mattun|mattun
        Maturbation|maturbation
        Maumauru|maumauru
        Mecha Boy|mecha boy
        Mecha Girl|mecha girl
        Medaka|medaka
        Megumi Fushiguro|megumi fushiguro
        Meibii|meibii
        Melanoma Kurosawa|melanoma kurosawa
        Melomil|melomil
        Melonbooks|melonbooks
        Meloriple|meloriple
        Meloshina|meloshina
        Menado Shisei|menado shisei
        Mentoru|mentoru
        Mepuchin|mepuchin
        Mercurylamp|mercurylamp
        Mermaid|mermaid
        Merman|merman
        Metromania|metromania
        Michihiko Shotamosuki|michihiko shotamosuki
        Mikanchu|mikanchu
        Miko Hoshi|miko hoshi
        Mikoshiba|mikoshiba
        Miku Abeno|miku abeno
        Mikumo|mikumo
        Mikuni|mikuni
        Milena|milena
        MILF|milf
        Milkdou Shoukai|milkdou shoukai
        Mimori|mimori
        Minakami Azuki|minakami azuki
        Minami Kiki|minami kiki
        Minana|minana
        Ming Ke|ming ke
        Mini Mamufuusen|mini mamufuusen
        Minigirl|minigirl
        Miniguy|miniguy
        Minmin Zemi|minmin zemi
        Minomushi Koujyou|minomushi koujyou
        Minotaur|minotaur
        Mio Junta|mio junta
        Mirai Trunks|mirai trunks
        Mirror|mirror
        Misc|misc
        Misohansen|misohansen
        Missing Cover|missing cover
        Misuke|misuke
        Mitaraidou|mitaraidou
        Mitsuki Touka|mitsuki touka
        Mitsuko|mitsuko
        Mitsukuni|mitsukuni
        Mitsuno|mitsuno
        Mixed:group|mixed:group
        Miyahama Ryou|miyahama ryou
        Miyami|miyami
        Mizuiro Ss|mizuiro ss
        Mizushima Akira|mizushima akira
        Mlt Sato|mlt sato
        MMF Threesome|mmf threesome
        MMM Threesome|mmm threesome
        Mmt Threesome|mmt threesome
        Moca Chocolate|moca chocolate
        Mochinchi|mochinchi
        Mochino Shiruko|mochino shiruko
        Mochocho|mochocho
        Mohorovicic Matako|mohorovicic matako
        Mokechi|mokechi
        Moki|moki
        Mokko|mokko
        Mokota|mokota
        Moku|moku
        Mole|mole
        Momo Rennji|momo rennji
        Momoiro Ginga Dan|momoiro ginga dan
        Momomo Gasshuukoku|momomo gasshuukoku
        Momotarou To Kintarou|momotarou to kintarou
        Momoya|momoya
        Mon|mon
        Monimonimo|monimonimo
        Monkey Boy|monkey boy
        Monkey Girl|monkey girl
        Moomin|moomin
        Moomoomilk|moomoomilk
        Moon Studio|moon studio
        Morino Kasumi|morino kasumi
        Morino Risu|morino risu
        Moriyama Inu|moriyama inu
        Mosaic Censorship|mosaic censorship
        Moth Girl|moth girl
        Mother|mother
        Mottsuo|mottsuo
        Mouse Boy|mouse boy
        Mouse Girl|mouse girl
        Mousou Kranke|mousou kranke
        Mr. Nagy|mr. nagy
        Mr. Uranojin|mr. uranojin
        Mtf Threesome|mtf threesome
        Muchuu Yakou|muchuu yakou
        Mucus|mucus
        Muhou Chitai|muhou chitai
        Mui.|mui.
        Multi-Work Series|multi-work series
        Multimouth Blowjob|multimouth blowjob
        Multipanel Sequence|multipanel sequence
        Multiple Breasts|multiple breasts
        Multiple Footjob|multiple footjob
        Multiple Nipples|multiple nipples
        Multiple Orgasm|multiple orgasm
        Multiple Pairings|multiple pairings
        Multiple Paizuri|multiple paizuri
        Munchener Illustrierte|munchener illustrierte
        Muni|muni
        Mura Mura|mura mura
        Murai Toyo|murai toyo
        Murasaki Reika|murasaki reika
        Mushoku|mushoku
        Muto Ichiru|muto ichiru
        Mutsu Minato|mutsu minato
        Mutsuki Yotsuka|mutsuki yotsuka
        Myuma Subaru|myuma subaru
        Myuu|myuu
        N-Yama|n-yama
        Nafuda|nafuda
        Nagamozu|nagamozu
        Nagarera|nagarera
        Nagashima|nagashima
        Nagisa Aya|nagisa aya
        Nagise Yuito|nagise yuito
        Nakaasa|nakaasa
        Nakamori Kyoko|nakamori kyoko
        Nakamura Kafka|nakamura kafka
        Nakata Akira|nakata akira
        Namaniku|namaniku
        Namenuru|namenuru
        Namiitcho|namiitcho
        Nanao Rion|nanao rion
        Nanase Tia|nanase tia
        Nandemo Honpo|nandemo honpo
        Narumieru|narumieru
        Naruse Isa|naruse isa
        Natsu No Ame|natsu no ame
        Natsukake Yuu|natsukake yuu
        Natsukawa Kagari|natsukawa kagari
        Natsukian|natsukian
        Natsume Shiki|natsume shiki
        Natsumi Kato|natsumi kato
        Natsumi Takao|natsumi takao
        Natsunoki|natsunoki
        Natsuzaki Natsumi|natsuzaki natsumi
        Natural 2|natural 2
        Natural Highs|natural highs
        Nebukur0|nebukur0
        Nego Blood|nego blood
        Nekomarudou Honpo|nekomarudou honpo
        Neo Tokugawake|neo tokugawake
        Niece|niece
        Niku To Mame|niku to mame
        Nikumamire|nikumamire
        Ninja|ninja
        Ninomae|ninomae
        Ninomiya Tika|ninomiya tika
        Ninsinsheep|ninsinsheep
        Nintai Akira|nintai akira
        Nipple Expansion|nipple expansion
        Nise Akasha Kai|nise akasha kai
        Nise Tsuruta Hirohisa|nise tsuruta hirohisa
        Nishifu|nishifu
        Nishigaki Meiro|nishigaki meiro
        Nishiki Ai|nishiki ai
        Nishinotes.|nishinotes.
        Nishiyamaa|nishiyamaa
        Nito Inko|nito inko
        Niyakko Srt|niyakko srt
        No Balls|no balls
        No Penetration|no penetration
        Nomiya Keyo|nomiya keyo
        Non-H|non-h
        Non-H Game Manual|non-h game manual
        Non-H Imageset|non-h imageset
        Non-Nude|non-nude
        Non-Ya|non-ya
        Nonoda Yamato|nonoda yamato
        Nonperman|nonperman
        Noodlemie|noodlemie
        Noomiso Tsurutsuru|noomiso tsurutsuru
        Noraneko Nicole|noraneko nicole
        Norizuki Jin|norizuki jin
        Nose Fuck|nose fuck
        Novel|novel
        November|november
        Nozaki Makoto|nozaki makoto
        Nude Male Clothed Female|nude male clothed female
        Nudism|nudism
        Nudity Only|nudity only
        Nug|nug
        Nuka|nuka
        Nuki Entertainment|nuki entertainment
        Nurumayu Tei|nurumayu tei
        Nyakana|nyakana
        Nyanko Catharsis|nyanko catharsis
        Nyowawa|nyowawa
        Object Insertion Only|object insertion only
        Ochine|ochine
        Oda Nobuna|oda nobuna
        Ogura Aya|ogura aya
        Ohkaneda Hirota|ohkaneda hirota
        Oishinbo|oishinbo
        Okayu Club|okayu club
        Okemaruta|okemaruta
        Oki Yuri|oki yuri
        Okinawa Mikan|okinawa mikan
        Okita Nao Hiro|okita nao hiro
        Okizarisu|okizarisu
        Okmonook|okmonook
        Okutani Ukyo|okutani ukyo
        Ol-San|ol-san
        Old Lady|old lady
        Old Man|old man
        Olga-Time Slap|olga-time slap
        Omae No Hatake Ga Suki|omae no hatake ga suki
        Omorashi|omorashi
        Onechin|onechin
        Onechinchi|onechinchi
        Onikuman|onikuman
        Onsen|onsen
        Onsen Panda|onsen panda
        Onushi|onushi
        Ooban Koban|ooban koban
        Oodake Kitama|oodake kitama
        Ooguchi Aori|ooguchi aori
        Ootake Hokuma|ootake hokuma
        Ootsuka Saki|ootsuka saki
        Oouchi Yamakawada|oouchi yamakawada
        Oppai Loli|oppai loli
        Ore P 2-Gou|ore p 2-gou
        Orihimeya|orihimeya
        Osake Renmei|osake renmei
        Osawagase Paradise|osawagase paradise
        Oshiri|oshiri
        Ossama|ossama
        Otaku Life Japan|otaku life japan
        Otoko|otoko
        Otokofutanari|otokofutanari
        Otona No Marushiki|otona no marushiki
        Otter Boy|otter boy
        Otter Girl|otter girl
        Ottoseinyan|ottoseinyan
        Ousama Game|ousama game
        Out Of Order|out of order
        Oyakodon|oyakodon
        Oyasumi.|oyasumi.
        Pacifier|pacifier
        Panda Boy|panda boy
        Panda Girl|panda girl
        Pandaj|pandaj
        Patricia O Sullivan|patricia o sullivan
        Payapaya Mambo De U|payapaya mambo de u
        Pegging|pegging
        Penpenmaru|penpenmaru
        Perrine|perrine
        Personality Excretion|personality excretion
        Pet Life|pet life
        Peyoda|peyoda
        Pia Carrot|pia carrot
        Pig Girl|pig girl
        Pig Man|pig man
        Pikota|pikota
        Pinez|pinez
        Pink Dragon|pink dragon
        Pink Tarte|pink tarte
        Pinky Web|pinky web
        Pixel Art|pixel art
        Plant Boy|plant boy
        Plant Girl|plant girl
        Playmaker|playmaker
        Policeman|policeman
        Policewoman|policewoman
        Pollensalta|pollensalta
        Pometa|pometa
        Ponygirl|ponygirl
        Poor Grammar|poor grammar
        Porunamin C|porunamin c
        Poyo Namasute|poyo namasute
        Priest|priest
        Prohibited Content|prohibited content
        Pulimi|pulimi
        Puramai Zero|puramai zero
        Purplian|purplian
        Purupyon|purupyon
        Pussyboys Only|pussyboys only
        Puuzaki Puuna|puuzaki puuna
        Qow|qow
        Raccoon Boy|raccoon boy
        Raccoon Girl|raccoon girl
        Race Queen|race queen
        Rairai Hito|rairai hito
        Rakkan Shugi|rakkan shugi
        Rakko No Kobeya|rakko no kobeya
        Ramonii|ramonii
        Random Skipper|random skipper
        Randou Mineru|randou mineru
        Rano Lalanox|rano lalanox
        Ranzal|ranzal
        Rariatoo|rariatoo
        Rayasi|rayasi
        Rayzhai|rayzhai
        Real Doll|real doll
        Realporn|realporn
        Rebaudio|rebaudio
        Redraw|redraw
        Remember Day|remember day
        Replaced|replaced
        Reuben|reuben
        Rewrite|rewrite
        Rhinoceros|rhinoceros
        Ring Gag|ring gag
        Ririkaruski|ririkaruski
        Rondo Of Swords|rondo of swords
        Roost|roost
        Roppongi|roppongi
        Roseaki|roseaki
        Rouen|rouen
        Rough Grammar|rough grammar
        Rough Kitsu|rough kitsu
        Rough Translation|rough translation
        Roukaku|roukaku
        Ruby Tuesday|ruby tuesday
        Rudeus Greyrat|rudeus greyrat
        Runner|runner
        Rutsakan Rekki|rutsakan rekki
        Rw Godom|rw godom
        Ryokado|ryokado
        Ryou|ryou
        Ryoujoku|ryoujoku
        Ryouki No Ori|ryouki no ori
        Ryuji|ryuji
        Ryuuguu|ryuuguu
        Ryuunokke|ryuunokke
        Ryuuzaki|ryuuzaki
        Sabatama Yumi|sabatama yumi
        Sagamani|sagamani
        Sagata Yodjirou|sagata yodjirou
        Sagata Yojirou|sagata yojirou
        Saidaime Tamabukuro Yaburu|saidaime tamabukuro yaburu
        Saiki Yoshikazu|saiki yoshikazu
        Saimin Koubou|saimin koubou
        Sajimoka Aca|sajimoka aca
        Saka169|saka169
        Sakurafubuki|sakurafubuki
        Sakuragi Piroko|sakuragi piroko
        Salad Resort|salad resort
        Sample|sample
        Sanba So|sanba so
        Sangoku Koi Senki|sangoku koi senki
        Sanhuro|sanhuro
        Sansangoya|sansangoya
        Sanuki Udon Jin|sanuki udon jin
        Sara Midorikawa|sara midorikawa
        Sasada Aki|sasada aki
        Sasara Somae|sasara somae
        Sato Shirakuma|sato shirakuma
        Satou Nanki|satou nanki
        Satsutaba Jenga|satsutaba jenga
        Sauna|sauna
        Sawaru|sawaru
        Sayama Yukihiro|sayama yukihiro
        Sayama-Gumi|sayama-gumi
        Scanmark|scanmark
        Scat|scat
        Screenshots|screenshots
        Screw|screw
        Sei Shonagon|sei shonagon
        Seigun Yuugekitai|seigun yuugekitai
        Seikan Nekoguruma|seikan nekoguruma
        Seinen Hormone|seinen hormone
        Seinyanko Gakuen|seinyanko gakuen
        Seki Hirame|seki hirame
        Seliph|seliph
        Selkiro|selkiro
        Sendai Oni|sendai oni
        Senoo Hibiteru|senoo hibiteru
        Senryou|senryou
        Sentou|sentou
        Sesshouin Kiara|sesshouin kiara
        Seto Ryouko|seto ryouko
        Shade No Urahime|shade no urahime
        Shapening|shapening
        Shark Boy|shark boy
        Shark Girl|shark girl
        Sheep Boy|sheep boy
        Sheep Girl|sheep girl
        Shibainu Lab|shibainu lab
        Shibaura|shibaura
        Shichiten Hattou|shichiten hattou
        Shidou Senku|shidou senku
        Shiiguchi Iruha|shiiguchi iruha
        Shijou Nanaca|shijou nanaca
        Shikakui Kyomu|shikakui kyomu
        Shimaidon|shimaidon
        Shimeji Sou|shimeji sou
        Shimejimo|shimejimo
        Shimizu Yume|shimizu yume
        Shimuro|shimuro
        Shinca Yuma|shinca yuma
        Shinooka Fukuenchou|shinooka fukuenchou
        Shinsenyasai|shinsenyasai
        Shiohachi|shiohachi
        Shiohara|shiohara
        Shiokaze Toride|shiokaze toride
        Shiromaru|shiromaru
        Shirotanuki|shirotanuki
        Shirudamari|shirudamari
        Shiseido Tsubaki|shiseido tsubaki
        Shishi|shishi
        Shizawa|shizawa
        Shokuyou Bond|shokuyou bond
        Shomunona|shomunona
        Shotacon|shotacon
        Shotata|shotata
        Shunga No Hassan|shunga no hassan
        Shunjuu Matsuri|shunjuu matsuri
        Shuraba Goya|shuraba goya
        Shuseikan|shuseikan
        Shuuyu Koukin|shuuyu koukin
        Siansi|siansi
        Sigaro|sigaro
        Sigeo|sigeo
        Silicon Valley|silicon valley
        Siomi|siomi
        Siruto|siruto
        Sister|sister
        Skeb|skeb
        Skeleton|skeleton
        Sketch Lines|sketch lines
        Skunk Boy|skunk boy
        Skunk Girl|skunk girl
        Slime Boy|slime boy
        Slime Girl|slime girl
        Small Breasts|small breasts
        Smts|smts
        Smys|smys
        Snail Girl|snail girl
        Snake Boy|snake boy
        Snake Girl|snake girl
        Sole Dickgirl|sole dickgirl
        Sole Female|sole female
        Sole Fmelae|sole fmelae
        Sole Male|sole male
        Sole Pussyboy|sole pussyboy
        Soranosuzume|soranosuzume
        Sorimachi Ayako|sorimachi ayako
        Sosso|sosso
        Souda|souda
        Soudeiba|soudeiba
        Soul Taker|soul taker
        Soushuuhen|soushuuhen
        Souzou-Shin|souzou-shin
        Spider Boy|spider boy
        Spider Girl|spider girl
        Split Tongue|split tongue
        Squid Boy|squid boy
        Squid Girl|squid girl
        Squirrel Boy|squirrel boy
        Squirrel Girl|squirrel girl
        Squirting|squirting
        Ssbbm|ssbbm
        Ssbbw|ssbbw
        Stacia|stacia
        Steward|steward
        Story Arc|story arc
        Strand Infinity|strand infinity
        Studio Crimson|studio crimson
        Studio Dragonov|studio dragonov
        Studio Ikkatsumajin|studio ikkatsumajin
        Suemi Junkyoutai|suemi junkyoutai
        Suidousui|suidousui
        Suiseimushi|suiseimushi
        Sukimatyaya|sukimatyaya
        Sukuna|sukuna
        Sukurinton|sukurinton
        Sumino Mikan|sumino mikan
        Sumire|sumire
        Suo Mifumu|suo mifumu
        Suspended|suspended
        Suzuki Hotaru|suzuki hotaru
        Suzunashi Rei|suzunashi rei
        Suzushiro Yakumo|suzushiro yakumo
        Swaro|swaro
        Swimming Pool|swimming pool
        Syake-Ama|syake-ama
        T-Sunds|t-sunds
        Ta-Bou|ta-bou
        Tachikawa Natsuki|tachikawa natsuki
        Taimanin|taimanin
        Taiyou No Nishi|taiyou no nishi
        Taka Haru|taka haru
        Takakujyu|takakujyu
        Takap|takap
        Takauka Shiki|takauka shiki
        Takaya A Ichirou|takaya a ichirou
        Takeda U-Ichi|takeda u-ichi
        Takeefu|takeefu
        Tall Girl|tall girl
        Tall Man|tall man
        Tamasaburou Syuntou|tamasaburou syuntou
        Tanimura Kawori|tanimura kawori
        Tankoubon|tankoubon
        Tannen|tannen
        Tappuri Fueru|tappuri fueru
        Tara. Ko|tara. ko
        Tarcho|tarcho
        Taru 519 Shukka|taru 519 shukka
        Tasu|tasu
        Tatsuhide|tatsuhide
        Tatsuma Chima|tatsuma chima
        Tawashi|tawashi
        Tease Comix|tease comix
        Tee Crown|tee crown
        Tenjou Shio|tenjou shio
        Tenkai Arahoushi|tenkai arahoushi
        Tennouji Masamichi|tennouji masamichi
        Tenpai|tenpai
        Terasumc|terasumc
        Tetsutaro Chiba|tetsutaro chiba
        Tetsutetsu Tetsutetsu|tetsutetsu tetsutetsu
        Tezuka|tezuka
        Themeless|themeless
        Thirty8ght|thirty8ght
        Thresholdouhu|thresholdouhu
        Tilia|tilia
        Time Stop|time stop
        Time Travel Tondekeman|time travel tondekeman
        Tinkamo|tinkamo
        Tipii|tipii
        Tkf|tkf
        Tocori|tocori
        Tokaku|tokaku
        Tokiwa Natsu|tokiwa natsu
        Tokkan Magasashi Musume|tokkan magasashi musume
        Tokkou Yarou Ap Team|tokkou yarou ap team
        Tokori|tokori
        Tomadoiki|tomadoiki
        Tomboy|tomboy
        Tomgirl|tomgirl
        Tomikadou|tomikadou
        Tomobukiya|tomobukiya
        Tomy F Rou|tomy f rou
        Tonami|tonami
        Tonari Toyama|tonari toyama
        Tooku No Mura|tooku no mura
        Tooyama Ginshirou|tooyama ginshirou
        Toppinparari No Puu|toppinparari no puu
        Torigoe Takumi|torigoe takumi
        Toropikku|toropikku
        Toshiyuki Sawada|toshiyuki sawada
        Totte|totte
        Tounyuu Koujou|tounyuu koujou
        Toure|toure
        Tousou Kasoku|tousou kasoku
        Tribadism|tribadism
        Triple Vaginal|triple vaginal
        Truffe|truffe
        Tsuki Inukoya|tsuki inukoya
        Tsunaka|tsunaka
        Tsuta Hiroko|tsuta hiroko
        Ttf Threesome|ttf threesome
        Ttm Threesome|ttm threesome
        Ttt Threesome|ttt threesome
        Turisasu|turisasu
        Turquoise|turquoise
        Twins|twins
        Tyria|tyria
        Uguisu Anko|uguisu anko
        Ujiie Kein|ujiie kein
        Umani|umani
        Umi Ryuusaki|umi ryuusaki
        Una Don|una don
        Uncensored|uncensored
        Uncle|uncle
        Unholy Sanctuary|unholy sanctuary
        Uni Unio|uni unio
        Unko Quality|unko quality
        Uno Shojo Sano Bijo|uno shojo sano bijo
        Unusual Insertions|unusual insertions
        Upset|upset
        Urako|urako
        Urano Meshiya|urano meshiya
        Uro Kojiki|uro kojiki
        Ushitora Kimon|ushitora kimon
        Uso Koi Shigure|uso koi shigure
        Ustilago Nuda|ustilago nuda
        Usuk|usuk
        Utatane|utatane
        Uurin|uurin
        Uyuu|uyuu
        Uzuki Miya|uzuki miya
        Vaginal Birth|vaginal birth
        Vaginal Sticker|vaginal sticker
        Valkyria Revolution|valkyria revolution
        Variant Set|variant set
        Vildred|vildred
        Viper Btr|viper btr
        Viper V6|viper v6
        Viper V8|viper v8
        Virginity|virginity
        Virginneko|virginneko
        Vivid Palette|vivid palette
        Vomit|vomit
        Vore|vore
        Wagamama High|wagamama high
        Wagni|wagni
        Wajima24|wajima24
        Walkure|walkure
        Watermarked|watermarked
        Watosu Mama|watosu mama
        Webtoon|webtoon
        Western|western
        Western Cg|western cg
        Western Imageset|western imageset
        Western Non-H|western non-h
        Whale|whale
        Widow|widow
        Widower|widower
        Wingjob|wingjob
        Wolf Boy|wolf boy
        Wolf Girl|wolf girl
        Xbm Studio|xbm studio
        Xenoblade Chronicles|xenoblade chronicles
        Xera|xera
        Yabuki|yabuki
        Yadou Nozomi|yadou nozomi
        Yagi Yahagiko|yagi yahagiko
        Yakicir|yakicir
        Yakumo Ryojin|yakumo ryojin
        Yamada Hina|yamada hina
        Yamada Pan|yamada pan
        Yamada Tasaku|yamada tasaku
        Yamagata Kokoro|yamagata kokoro
        Yamanashi Rei|yamanashi rei
        Yamayamaya|yamayamaya
        Yamazaki Takumi|yamazaki takumi
        Yami Books|yami books
        Yan2252|yan2252
        Yanagie Terasu|yanagie terasu
        Yaoi|yaoi
        Yapi|yapi
        Yasagure|yasagure
        Yashima Tetsuya|yashima tetsuya
        Yasu G|yasu g
        Yatumi|yatumi
        Yigami|yigami
        Yogarasu|yogarasu
        Yohchi|yohchi
        Yokotaya|yokotaya
        Yokoyama Mitsuko|yokoyama mitsuko
        Yoro|yoro
        Yoshida Kazuya|yoshida kazuya
        Yoshihiko Takeo|yoshihiko takeo
        Yoshinari|yoshinari
        Yoshino Ruto|yoshino ruto
        Yugioh Arc-V|yugioh arc-v
        Yukikuni|yukikuni
        Yukiyorii|yukiyorii
        Yukkuri|yukkuri
        Yume Aoi|yume aoi
        Yumeharo|yumeharo
        Yumeki|yumeki
        Yumemori|yumemori
        Yumishima Rino|yumishima rino
        Yunisuke|yunisuke
        Yurayura|yurayura
        Yuri|yuri
        Yuuki Chizuco|yuuki chizuco
        Yuunagi Gaibuta|yuunagi gaibuta
        Yuunagi Komotaro|yuunagi komotaro
        Yuushinron|yuushinron
        Yuzu Pyon|yuzu pyon
        Zenten Ukemi Tomo No Kai|zenten ukemi tomo no kai
        Zubatto|zubatto
    """
