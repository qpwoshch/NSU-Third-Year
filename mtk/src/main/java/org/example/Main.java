package org.example;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.scene.paint.Color;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class Main extends Application {
    private CheckBox enableLogging;  // Объявление переменной для чекбокса логирования
    private TextArea outputArea;     // Добавлено объявление переменной outputArea

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Синтаксический анализатор: декларация комплексных констант Pascal");

        // --- Элементы GUI ---
        TextArea inputArea = new TextArea();
        inputArea.setPromptText("Введите строку, например:\nconst z = (3.5, -2.1);");

        outputArea = new TextArea();  // Инициализируем outputArea
        outputArea.setEditable(false);
        outputArea.setPromptText("Результаты разбора появятся здесь...");

        Button checkButton = new Button("Проверить");
        checkButton.setMaxWidth(Double.MAX_VALUE);

        // --- Инициализация чекбокса для логирования ---
        enableLogging = new CheckBox("Включить логирование ошибок");

        // --- Обработчик кнопки ---
        checkButton.setOnAction(e -> {
            String text = inputArea.getText().trim();
            if (text.isEmpty()) {
                outputArea.setText("Введите текст для анализа.");
                return;
            }

            String[] lines = text.split("\n");  // Разбиваем ввод на строки
            StringBuilder result = new StringBuilder();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Обрабатываем каждую строку
                try {
                    ComplexConstParser parser = new ComplexConstParser(line, enableLogging.isSelected());
                    List<String> errors = parser.parse();
                    if (errors.isEmpty()) {
                        result.append("✅ Синтаксис корректен для строки: ").append(line).append("\n");
                    } else {
                        result.append("❌ Ошибки для строки: ").append(line).append("\n");
                        result.append(String.join("\n", errors)).append("\n");
                    }
                } catch (Exception ex) {
                    result.append("❌ Ошибка при анализе строки: ").append(line).append("\n");
                    result.append(ex.getMessage()).append("\n");
                }
            }

            outputArea.setText(result.toString());  // Выводим результаты
        });

        // --- Справка ---
        String grammarInfo = """
                Грамматика (BNF):
                <ConstDecl>  ::= 'const' <Identifier> '=' <ComplexConst> ';'
                <ComplexConst> ::= '(' <RealConst> ',' <RealConst> ')'
                <RealConst>  ::= [ '+' | '-' ] <UnsignedReal>
                <UnsignedReal> ::= <DigitSeq> | <DigitSeq> '.' <DigitSeq>
                <DigitSeq>   ::= <Digit> { <Digit> }
                <Identifier> ::= <Letter> { <Letter> | <Digit> }

                Пример корректной строки:
                const z = (3.5, -2.1);
                """;

        TextArea helpArea = new TextArea(grammarInfo);
        helpArea.setEditable(false);
        helpArea.setWrapText(true);

        TabPane tabs = new TabPane();
        Tab tabEditor = new Tab("Редактор", new VBox(10, inputArea, checkButton, outputArea));
        Tab tabHelp = new Tab("Справка", helpArea);
        tabEditor.setClosable(false);
        tabHelp.setClosable(false);

        // --- Настройки ---
        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton lightTheme = new RadioButton("Светлая тема");
        RadioButton darkTheme = new RadioButton("Тёмная тема");
        lightTheme.setToggleGroup(themeGroup);
        darkTheme.setToggleGroup(themeGroup);
        lightTheme.setSelected(true);
        lightTheme.setOnAction(e -> applyLightTheme(stage, inputArea, outputArea));
        darkTheme.setOnAction(e -> applyDarkTheme(stage, inputArea, outputArea));

        VBox themeBox = new VBox(10, lightTheme, darkTheme);

        TextField grammarDescription = new TextField("Вставьте описание грамматики...");

        Button loadFileButton = new Button("Загрузить файл");
        Button saveFileButton = new Button("Сохранить файл");

        loadFileButton.setOnAction(e -> loadFile(inputArea));
        saveFileButton.setOnAction(e -> saveFile(inputArea));

        VBox settingsBox = new VBox(10, enableLogging, grammarDescription, loadFileButton, saveFileButton, themeBox);
        Tab tabSettings = new Tab("Настройки", settingsBox);
        tabSettings.setClosable(false);

        tabs.getTabs().addAll(tabEditor, tabHelp, tabSettings);

        VBox root = new VBox(10, tabs);
        root.setPadding(new Insets(10));

        stage.setScene(new Scene(root, 700, 500));
        stage.show();
    }

    // Применение светлой темы
    private void applyLightTheme(Stage stage, TextArea inputArea, TextArea outputArea) {
        stage.getScene().setFill(Color.WHITE);
        inputArea.setStyle("-fx-background-color: white; -fx-text-fill: black;");
        outputArea.setStyle("-fx-background-color: white; -fx-text-fill: black;");
    }

    // Применение тёмной темы
    private void applyDarkTheme(Stage stage, TextArea inputArea, TextArea outputArea) {
        stage.getScene().setFill(Color.DARKGRAY); // Устанавливаем цвет фона сцены
        inputArea.setStyle("-fx-background-color: black; -fx-text-fill: red;"); // Темный фон и белый текст для TextArea
        outputArea.setStyle("-fx-background-color: black; -fx-text-fill: red;"); // Темный фон и белый текст для вывода
    }

    // Методы для загрузки и сохранения файлов
    private void loadFile(TextArea inputArea) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = fileChooser.showOpenDialog(null);

        // Проверяем, что файл был выбран
        if (file != null) {
            try {
                // Чтение содержимого файла в строку
                String content = new String(Files.readAllBytes(file.toPath()));
                inputArea.setText(content);  // Загружаем содержимое файла в inputArea

                // Теперь анализируем файл как обычный ввод
                String text = content.trim();
                if (text.isEmpty()) {
                    outputArea.setText("Введите текст для анализа.");
                    return;
                }

                // Использование чекбокса для включения логирования
                ComplexConstParser parser = new ComplexConstParser(text, enableLogging.isSelected());
                List<String> errors = parser.parse();
                if (errors.isEmpty()) {
                    outputArea.setText("✅ Синтаксис корректен.");
                } else {
                    outputArea.setText("❌ Ошибки:\n" + String.join("\n", errors));
                }

            } catch (IOException ex) {
                ex.printStackTrace();
                outputArea.setText("❌ Ошибка при загрузке файла: " + ex.getMessage());
            }
        } else {
            outputArea.setText("❌ Файл не выбран.");
        }
    }

    private void saveFile(TextArea inputArea) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try {
                Files.write(file.toPath(), inputArea.getText().getBytes());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    // Логирование ошибок
    private void logError(Exception ex) {
        System.out.println("Ошибка: " + ex.getMessage());
    }

    // --------------------------
    // Встроенный синтаксический анализатор
    // --------------------------
//    static class ComplexConstParser {
//        private final String input;
//        private int pos = 0;
//        private boolean loggingEnabled;
//        private List<String> errors = new ArrayList<>();
//
//        public ComplexConstParser(String input, boolean loggingEnabled) {
//            this.input = input.trim();
//            this.loggingEnabled = loggingEnabled;
//        }
//
//        private char current() {
//            return pos < input.length() ? input.charAt(pos) : '\0';
//        }
//
//        private void consume() {
//            pos++;
//        }
//
//        private void expect(char c) {
//            if (current() != c) error("Ожидался символ '" + c + "'");
//            consume();
//        }
//
//        private void error(String msg) {
//            errors.add("Ошибка в позиции " + pos + ": " + msg);
//            if (loggingEnabled) logError(new RuntimeException(msg));
//        }
//
//        private void skipSpaces() {
//            while (Character.isWhitespace(current())) consume();
//        }
//
//        public List<String> parse() {
//            skipSpaces();
//            if (!input.startsWith("const", pos))
//                error("Ожидалось ключевое слово 'const'");
//            pos += 5;
//            skipSpaces();
//            parseIdentifier();
//            skipSpaces();
//            expect('=');
//            skipSpaces();
//            parseComplexConst();
//            skipSpaces();
//            expect(';');
//
//            return errors;
//        }
//
//        private void parseIdentifier() {
//            if (!Character.isLetter(current()))  // Проверка на то, что идентификатор начинается с буквы
//                error("Ожидался идентификатор, который начинается с буквы");
//            consume();
//            while (Character.isLetterOrDigit(current()))  // Идентификатор может состоять из букв и цифр
//                consume();
//        }
//
//        private void parseComplexConst() {
//            expect('(');
//            skipSpaces();
//            parseRealConst();
//            skipSpaces();
//
//            // Если есть запятая, то ожидаем еще одно число
//            if (current() == ',') {
//                consume();
//                skipSpaces();
//                parseRealConst();
//            } else {
//                error("Ожидалось два значения в кортеже.");
//            }
//            skipSpaces();
//            expect(')');
//        }
//
//        private void parseRealConst() {
//            if (current() == '+' || current() == '-') consume();  // Обрабатываем знак
//            parseUnsignedReal();
//        }
//
//        private void parseUnsignedReal() {
//            parseDigitSeq();
//            if (current() == '.') {
//                consume();
//                parseDigitSeq();  // Обрабатываем дробную часть
//            }
//        }
//
//        private void parseDigitSeq() {
//            if (!Character.isDigit(current()))  // Первая цифра обязательна
//                error("Ожидалась цифра");
//            while (Character.isDigit(current()))  // Чтение цифр
//                consume();
//        }
//
//        // Логирование ошибок
//        private void logError(Exception ex) {
//            System.out.println("Ошибка: " + ex.getMessage());
//        }
//    }
}
