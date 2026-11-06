-- Sign-up used to set display_name to the email's local part, or to the phone number, and it is public by default.
-- Clear those so profiles show the username instead. Gmail addresses were canonicalized after some sign-ups
-- (dots and +tags dropped), and Taiwan phone numbers moved from 09xxxxxxxx to +8869xxxxxxxx, so a display name
-- matches those older forms too. A name the user chose that happens to equal
-- one of these is cleared as well. Idempotent.
UPDATE vomatt.users
SET display_name = NULL
WHERE display_name = phone_number
   OR (display_name ~ '^09[0-9]{8}$' AND phone_number = '+886' || substr(display_name, 2))
   OR display_name = split_part(email, '@', 1)
   OR (split_part(email, '@', 2) = 'gmail.com'
       AND replace(split_part(display_name, '+', 1), '.', '') = split_part(email, '@', 1));
