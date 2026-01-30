package org.example;

import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

public class ComplexConstParser {
    private final String input;
    private final boolean loggingEnabled;
    private final List<String> errors = new ArrayList<>();

    public ComplexConstParser(String input, boolean loggingEnabled) {
        this.input = input.trim();
        this.loggingEnabled = loggingEnabled;
    }

    public List<String> parse() {
        CharStream stream = CharStreams.fromString(input);
        ComplexConstDeclLexer lexer = new ComplexConstDeclLexer(stream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ComplexConstDeclParser parser = new ComplexConstDeclParser(tokens);

        // Важно: включаем режим восстановления, чтобы парсер дошёл до конца даже при ошибках
        parser.setErrorHandler(new DefaultErrorStrategy() {
            @Override
            public void recover(Parser recognizer, RecognitionException e) {
                super.recover(recognizer, e);
            }

            @Override
            public Token recoverInline(Parser recognizer) throws RecognitionException {
                return super.recoverInline(recognizer);
            }
        });

        parser.removeErrorListeners();

        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer,
                                    Object offendingSymbol,
                                    int line,
                                    int charPositionInLine,
                                    String msg,
                                    RecognitionException e) {
                String errorMsg = "Ошибка в строке " + line + ", позиция " + (charPositionInLine + 1) + ": " + msg;
                errors.add(errorMsg);

                if (loggingEnabled) {
                    System.err.println("ANTLR ошибка: " + errorMsg);
                }
            }
        });

        try {
            parser.program();  // стартуем с program → требует EOF

            // Если парсер дошёл до EOF без исключения — всё идеально
            return errors;
        } catch (Exception e) {
            // Если упали с исключением — значит, не смогли восстановиться
            if (errors.isEmpty()) {
                errors.add("Синтаксическая ошибка: неполная или неверная декларация");
            }
            return errors;
        } finally {
            // Ключевая проверка: даже если были ошибки, но не весь ввод потреблён — ошибка!
            if (parser.getCurrentToken().getType() != Token.EOF) {
                errors.add("Ошибка: отсутствует точка с запятой или незакрытые скобки (неполная конструкция)");
            }
        }
    }
}