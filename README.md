# TwineRtfl
Twine module to enable Rtfl scripting inside of documents in a PHP-like fashion

# Compiling
To compile, execute `./gradlew shadowJar` (Linux, BSD, Mac) or `gradlew.bat shadowJar` (Windows). The module jar will be built at `build/libs/` and can be placed in Twine's `modules/` directory to use.

# Usage
In order to use Rtfl scripting you must set `scripting` to `true` in `twine.yml`, or Rtfl templates will not be rendered and will be sent in plaintext. If you wante to render error messages instead of sending a default server error page, set `scriptExceptions` to `true` as well.

In order to use Rtfl scripting, you must create `.rtfm` files. "RTFM" in this case stands for **Rtf**l **M**arkup. RTFM files hold our Rtfl templates, and much like in PHP, valid HTML is valid Rtfl markup.
RTFM files may reside in any Twine domain directory but not the static files directory.

# Syntax
Like PHP, Rtfl scripts are initiated by an opener string (&lt;?rtfl) and terminated by a closer string (?&gt;).
Any code inside of those strings will be treated as Rtfl scripts and will not be appended to the document.
If any uncaught exceptions occur inside of these scripts, a server error page will be served and execution will halt. You can check which error occurred by looking at the Twine console, or if you have `scriptExceptions` enabled in `twine.yml` you will see the error displayed on the page. This setting is not recommended in production environments as it can expose sensitive information about scripts.

Script blocks are provided with both the Rtfl standard library and special variables and functions specifically for TwineRtfl. Documentation for those special functions and variables are provided [here](https://github.com/termermc/TwineRtfl/blob/master/src/main/resources/doc/twinertfl.html).

# Special blocks
Often you will need more than basic scripting ability, like for example if you need to insert a value without typing `append` or need a conditional block of HTML, or maybe need to loop through values and put HTML for each value.

The following basic blocks are available:

`<?rtfl= VALUE ?>` (sanitized insert)

`<?rtfl- VALUE ?>` (unsanitized insert)

`<?rtfl-if[CONDITION] MARKUP ?>` (if block) (scripts in the markup body are in a child scope)

`<?rtfl-if[CONDITION] MARKUP <else> MARKUP ?>` (if-else block) (scripts in the markup body are in a child scope)

`<?rtfl-for[VALUE in ARRAY] MARKUP ?>` (for loop block) (scripts in the markup body are in a child scope)

`<?rtfl-while[CONDITION] MARKUP ?>` (while loop block) (scripts in the markup body are in a child scope)

`<?rtfl-require[RELATIVE_PATH] ?>` (template require block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference) (any subsequent require calls to the provided file will be ignored)

`<?rtfl-include[RELATIVE_PATH] ?>` (template require block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-try MARKUP <catch[VARIABLE]> MARKUP ?>` (try-catch block) (all markup after `<catch[VARIABLE]>` will only be appended if an error occurs in the first markup block, and a variable with the name denoted by VARIABLE will be available containing the error string that occurred)

`<?rtfl-blocking MARKUP ?>` (blocking markup block)

Those are the basic blocks for appending values and markup.
Since Twine uses async I/O and an event loop, I/O operations must be asynchronous or they will block the event loop. Since Rtfl does not use asynchronous I/O, special blocks were created to do asynchronous operations and return their Rtfl value.
The following blocks return values as local variables in the current Rtfl scope or perform an asynchronous operation.

`<?rtfl-read-file[RELATIVE_PATH as VARIABLE] ?>` (read file block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-write-file[CONTENT to RELATIVE_PATH] ?>` (write file block) (CONTENT and RELATIVE_PATHmust be an Rtfl string, function call, or variable reference)

`<?rtfl-file-exists[RELATIVE_PATH as VARIABLE] ?>` (file exists block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-delete-file[RELATIVE_PATH] ?>` (file delete block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-mkdir[RELATIVE_PATH] ?>` (file create directory block) (RELATIVE_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-move-file[RELATIVE_PATH to NEW_PATH] ?>` (file move block) (RELATIVE_PATH and NEW_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-copy-file[RELATIVE_PATH to NEW_PATH ?>` (file copy block) (RELATIVE_PATH and NEW_PATH must be an Rtfl string, function call, or variable reference)

`<?rtfl-http[URL with OPTIONS as VARIABLE] ?>` (read HTTP block) (URL must be an Rtfl string, function call, or variable reference) (OPTIONS must be an Rtfl map, and can contain the follow values: method (string), params (map))

The following are database specific blocks:

`<?rtfl-db-connect[OPTIONS as VARIABLE] ?>` (database connect block) (OPTIONS must be a map generated by db_config()) (a new connection will not be created if a connection to the database already exists and hasn't been closed, instead the existing connection will be returned)

`<?rtfl-db-close[OPTIONS] ?>` (database close block) (OPTIONS must be a map generated by db_config())

`<?rtfl-db-query[QUERY on CONNECTION as VARIABLE] ?>` (database query block) (QUERY must be a map generated by query_prepare()) (CONNECTION must be a connection returned by rtfl-db-connect)

The following are hashing-related blocks:

`<?rtfl-hash[STRING as VARIABLE] ?>` (string hash block) (STRING must be an Rtfl string literal, function call, or variable reference) (hashes and salts the provided string with the Argon2 algorithm)

`<?rtfl-verify[STRING against HASH as VARIABLE] ?>` (hash verify block) (STRING and HASH must be an Rtfl string literal, function call, or variable reference) (checks the hash and salt combination string with the Argon2 algorithm)