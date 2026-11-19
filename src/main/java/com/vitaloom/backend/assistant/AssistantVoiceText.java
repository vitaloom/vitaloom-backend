package com.vitaloom.backend.assistant;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class AssistantVoiceText {
    private static final String WORD_LEFT = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_RIGHT = "(?![\\p{L}\\p{N}])";
    private static final String UNIT_WORD = "(?:không|một|hai|ba|bốn|năm|sáu|bảy|tám|chín|mốt|tư|lăm)";
    private static final String CLOCK_NUMBER = "(?:\\d{1,2}|mười(?:\\s+" + UNIT_WORD + ")?|"
            + UNIT_WORD + "(?:\\s+mươi(?:\\s+" + UNIT_WORD + ")?)?)";
    private static final Pattern DATE = word("hôm nay|hôm qua|hôm kia|(\\d{4}-\\d{2}-\\d{2})"
            + "|(" + CLOCK_NUMBER + ")(?:/|\\s+tháng\\s+)(" + CLOCK_NUMBER + ")(?:(?:/|\\s+năm\\s+)(\\d{4}))?");
    private static final Pattern TIME = word("(" + CLOCK_NUMBER + ")\\s*(?:[:h]|giờ)"
            + "(?:\\s*(" + CLOCK_NUMBER + "|rưỡi)(?:\\s*phút)?)?(?:\\s*(sáng|trưa|chiều|tối|đêm))?");
    private static final Pattern NOW = word("bây giờ|hiện tại|vừa (?:mới )?uống|lúc này|ngay");
    private static final String NUMBER_WORD = "(?:không|một|hai|ba|bốn|năm|sáu|bảy|tám|chín|mười|mươi|mốt|tư|lăm|nghìn|trăm|linh|lẻ)";
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\p{L}\\p{N}.,])"
            + "([0-9]{1,4}+(?:[.,][0-9]{1,3}+)?+|" + NUMBER_WORD + "(?:\\s++" + NUMBER_WORD + ")*+)"
            + "\\s*+(ml|mi li lít|mililit|mililít|lít|l)" + WORD_RIGHT);
    private static final Pattern HOURS = Pattern.compile("(.+?)\\s*(tiếng|giờ|h)(?:\\s*rưỡi|\\s*(\\d{1,2})(?:\\s*phút)?)?");
    private static final Pattern MINUTES = Pattern.compile("(.+?)\\s*phút");
    private static final Map<String, Integer> UNITS = Map.of(
            "không", 0, "một", 1, "hai", 2, "ba", 3, "bốn", 4,
            "năm", 5, "sáu", 6, "bảy", 7, "tám", 8, "chín", 9);

    private final List<String> utterances;
    private final LocalDate today;

    AssistantVoiceText(List<String> utterances, LocalDate today) {
        this.utterances = utterances.stream().map(AssistantVoiceText::normalize).toList();
        this.today = today;
    }

    String quote(JsonNode args, String field) {
        var value = args.get(field);
        if (value == null) return null;
        require(value.isString());
        String phrase = normalize(value.stringValue());
        require(!phrase.isEmpty());
        sourceFor(phrase);
        return phrase;
    }

    private String sourceFor(String phrase) {
        String left = Character.isDigit(phrase.charAt(0)) ? "(?<![\\p{L}\\p{N}.,/-])" : WORD_LEFT;
        Pattern quote = Pattern.compile(left + Pattern.quote(phrase) + WORD_RIGHT);
        return utterances.reversed().stream().filter(text -> quote.matcher(text).find()).findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }

    int amountMl(String phrase) {
        require(phrase != null);
        var match = AMOUNT.matcher(sourceFor(phrase));
        require(match.find() && match.group().equals(phrase));
        String digits = match.group(1);
        boolean liters = match.group(2).equals("lít") || match.group(2).equals("l");
        require(!match.find());
        require(liters || (!digits.contains(",") && !digits.contains(".")));
        BigDecimal amount = Character.isDigit(digits.charAt(0))
                ? new BigDecimal(digits.replace(',', '.')) : BigDecimal.valueOf(number(digits));
        int ml = (liters ? amount.movePointRight(3) : amount).intValueExact();
        require(ml >= 1 && ml <= 2000);
        return ml;
    }

    Instant waterTime(String phrase, Instant receivedAt) {
        if (phrase == null) {
            require(utterances.stream().noneMatch(text -> TIME.matcher(text).find() || DATE.matcher(text).find()));
            return receivedAt;
        }
        String source = sourceFor(phrase);
        requireCompleteWhen(DATE, phrase, source);
        requireCompleteWhen(TIME, phrase, source);
        if (!TIME.matcher(phrase).find()) {
            require(!TIME.matcher(source).find() && (DATE.matcher(phrase).find() ? date(phrase).equals(today)
                    : NOW.matcher(phrase).find()));
            return receivedAt;
        }
        require(DATE.matcher(phrase).find() || utterances.stream().noneMatch(text -> DATE.matcher(text).find()));
        Instant time = dateTime(phrase, false).atZone(AssistantVoiceActions.ZONE).toInstant();
        require(!time.isAfter(receivedAt));
        return time;
    }

    private static void requireCompleteWhen(Pattern pattern, String phrase, String source) {
        var spoken = pattern.matcher(source);
        if (!spoken.find()) return;
        var selected = pattern.matcher(phrase);
        require(selected.find() && spoken.group().equals(selected.group()) && !spoken.find());
    }

    int minutes(String phrase) {
        var minuteMatch = MINUTES.matcher(phrase);
        var hourMatch = HOURS.matcher(phrase);
        int minutes;
        if (hourMatch.matches()) {
            int hours = number(hourMatch.group(1).strip());
            require(hours <= 24);
            var extra = hourMatch.group(3);
            int remainder = extra != null ? Integer.parseInt(extra) : phrase.endsWith("rưỡi") ? 30 : 0;
            require(remainder < 60);
            minutes = hours * 60 + remainder;
        } else if (minuteMatch.matches()) {
            minutes = number(minuteMatch.group(1).strip());
        } else {
            throw new IllegalArgumentException();
        }
        require(minutes >= 1 && minutes <= 1440);
        return minutes;
    }

    LocalDate date(String phrase) {
        var match = DATE.matcher(phrase);
        require(match.find());
        requireCompleteToken(DATE, phrase, match.start(), match.group());
        LocalDate dayFromPhrase = switch (match.group()) {
            case "hôm nay" -> today;
            case "hôm qua" -> today.minusDays(1);
            case "hôm kia" -> today.minusDays(2);
            default -> {
                if (match.group(1) != null) yield LocalDate.parse(match.group(1));
                int month = number(match.group(3));
                int day = number(match.group(2));
                if (match.group(4) != null) yield LocalDate.of(Integer.parseInt(match.group(4)), month, day);
                yield LocalDate.of(today.getYear(), month, day);
            }
        };
        require(!match.find());
        return dayFromPhrase;
    }

    LocalDateTime dateTime(String phrase, boolean requireDay) {
        var match = TIME.matcher(phrase);
        require(match.find());
        requireCompleteToken(TIME, phrase, match.start(), match.group());
        LocalDate day = requireDay || DATE.matcher(phrase).find() ? date(phrase) : today;
        int hour = number(match.group(1));
        String minuteText = match.group(2);
        int minute = minuteText == null ? 0 : minuteText.equals("rưỡi") ? 30 : number(minuteText);
        if (match.group(3) != null) {
            hour = switch (match.group(3)) {
                case "sáng" -> hour == 12 ? 0 : hour;
                case "trưa", "chiều", "tối", "đêm" -> hour < 12 ? hour + 12 : hour;
                default -> throw new IllegalArgumentException();
            };
        }
        require(!match.find());
        return day.atTime(LocalTime.of(hour, minute));
    }

    private void requireCompleteToken(Pattern pattern, String phrase, int offset, String selected) {
        String source = sourceFor(phrase);
        var literal = Pattern.compile(WORD_LEFT + Pattern.quote(phrase) + WORD_RIGHT).matcher(source);
        var tokens = pattern.matcher(source);
        while (literal.find()) {
            if (tokens.find(literal.start() + offset) && tokens.start() == literal.start() + offset
                    && tokens.group().equals(selected)) return;
        }
        throw new IllegalArgumentException();
    }

    String feeling(String phrase) {
        require(phrase != null);
        require(!word("không\\s+" + Pattern.quote(phrase)).matcher(sourceFor(phrase)).find());
        return switch (phrase) {
            case "tốt", "thoải mái" -> "COMFORTABLE";
            case "bình thường" -> "NORMAL";
            case "mệt", "mệt mỏi" -> "TIRED";
            default -> throw new IllegalArgumentException();
        };
    }

    String feelingLabel(String feeling) {
        return switch (feeling) {
            case "COMFORTABLE" -> "tốt";
            case "NORMAL" -> "bình thường";
            default -> "mệt mỏi";
        };
    }

    private static int number(String value) {
        if (value.matches("\\d{1,4}")) return Integer.parseInt(value);
        int result = 0;
        String[] thousands = value.split("\\s+nghìn(?:\\s+|$)", -1);
        if (thousands.length == 2) {
            result += unit(thousands[0]) * 1000;
            value = thousands[1];
        }
        String[] hundreds = value.split("\\s+trăm(?:\\s+|$)", -1);
        if (hundreds.length == 2) {
            result += unit(hundreds[0]) * 100;
            value = hundreds[1];
        }
        value = value.replaceFirst("^(?:linh|lẻ)\\s+", "");
        if (value.isEmpty()) return result;
        if (value.equals("mười")) return result + 10;
        if (value.startsWith("mười ")) return result + 10 + unit(value.substring(5));
        String[] tens = value.split("\\s+mươi(?:\\s+|$)", -1);
        if (tens.length == 2) return result + unit(tens[0]) * 10 + (tens[1].isEmpty() ? 0 : unit(tens[1]));
        return result + unit(value);
    }

    private static int unit(String value) {
        Integer unit = UNITS.get(switch (value) {
            case "mốt" -> "một";
            case "tư" -> "bốn";
            case "lăm" -> "năm";
            default -> value;
        });
        require(unit != null);
        return unit;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException();
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile(WORD_LEFT + "(?:" + alternatives + ")" + WORD_RIGHT);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ").strip();
    }
}
