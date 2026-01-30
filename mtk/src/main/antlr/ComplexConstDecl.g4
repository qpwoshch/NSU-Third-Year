grammar ComplexConstDecl;

options {
    caseInsensitive = true;
}

program
    : constDecl EOF
    ;

constDecl
    : 'const' identifier '=' complexConst ';'
    ;

complexConst
    : '(' realConst ',' realConst ')'
    ;

realConst
    : ('+' | '-')? unsignedReal
    ;

unsignedReal
    : '.' digitSeq                // .5
    | digitSeq ('.' digitSeq?)?   // 123, 123., 123.456
    ;

digitSeq
    : DIGIT+
    ;

identifier
    : LETTER (LETTER | DIGIT)*
    ;

DIGIT   : [0-9];
LETTER  : [a-zA-Z_];

WS      : [ \t\r\n]+ -> skip ;