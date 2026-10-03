# Moving this seed into the `qecode` repository

This directory is the complete initial content of the new repository. It is parked inside jabiz only because the new
repository did not exist yet. Delete this file after the move.

1. Create an empty private repository `staunch0515/qecode` on GitHub (no README, no licence, no .gitignore).
2. From a jabiz checkout on `1.1/platform`:

```bash
git fetch origin 1.1/platform && git checkout 1.1/platform && git pull
mkdir ../qecode && cp -R docs/qecode-seed/. ../qecode/
cd ../qecode && rm HANDOFF.md
git init -b main && git add -A && git commit -m "Seed: plan, working rules, decisions, requirement log"
git remote add origin git@github.com:staunch0515/qecode.git && git push -u origin main
```

3. Remove the seed from jabiz:

```bash
cd ../jabiz && git rm -r docs/qecode-seed && git commit -m "Remove the qecode seed (moved to its own repository)" && git push
```

4. Put a copy of the planning PDF under `docs/requirements/source/` in `qecode` and commit it.
5. Give Claude Code access to `staunch0515/qecode` (GitHub app installation, then add the repository to the cloud environment)
   and start the next session there with: "Start phase P0 of docs/plan/development-plan.md; write the detailed P0 plan first."
