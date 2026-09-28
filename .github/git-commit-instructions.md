# Git Commit Instructions (for GitHub Copilot)

Please generate Git commit messages for this project according to the following rules:

### 1. Follow Conventional Commits 1.0.0 Specification
- **Format:** `<type>[optional scope]: <description>`
- **Common Types:**
    - `feat`: A new feature
    - `fix`: A bug fix
    - `docs`: Documentation only changes
    - `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc.)
    - `refactor`: A code change that neither fixes a bug nor adds a feature
    - `perf`: A code change that improves performance
    - `test`: Adding missing tests or correcting existing tests
    - `chore`: Maintenance tasks (build scripts, config files, tool updates, etc.)
    - `ci`: Changes to CI/CD configuration files and scripts

### 2. Language and Tone
- **Content Language:** Use **Traditional Chinese** (繁體中文) for all content.
- **Description Tone:** Use the imperative mood for the description (subject line), for example:
    - `feat(api): 新增訂單查詢 API`
    - `fix(auth): 修正 token 驗證邏輯`
- Be concise; clearly state "what was done" without being wordy.

### 3. Subject Line
- Limit to 50 characters or less.
- Do not end the line with a period.
- Use parentheses for the scope if applicable, e.g., `feat(user): ...`, `fix(order-service): ...`.

### 4. Body (If required)
- Separate the body from the subject line with a blank line.
- Briefly explain the "why" behind the change and the "main modification points."
- Use bullet points (`- ` or `* `) to list key details:
    - Describe primary changes.
    - Explain differences from previous behavior.
    - Mention potentially affected modules or risks.

### 5. Footer (Optional)
- For associated issues or tasks, use:
    - `Refs: #123`
    - `Closes: #123`
- For breaking changes, include:
    - `BREAKING CHANGE: <description of the breaking change>`

### 6. Additional Requirements
- One commit message should describe a logically consistent set of changes.
- If a change includes multiple types, choose the one that best represents the primary purpose.
- If the scope of the change is too large, prioritize suggesting that the changes be split into multiple commits, with messages generated for each.