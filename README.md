# System Design

A structured, from-scratch walkthrough of system design. Not a random collection of notes I made while prepping for interviews, but an actual path: start with the basic building blocks, move to how large systems are designed at a high level, then go deep into how individual components and classes are designed and coded at a low level.

This repo is my own learning turned into something usable by anyone else walking the same road.

## Why this exists

Most system design content online falls into one of two buckets. Either it's a two-minute YouTube short that throws buzzwords like "sharding" and "CAP theorem" at you with no context, or it's a 40-page whitepaper that assumes you already know everything. There's very little that takes a beginner by the hand and builds the picture up one brick at a time, while still going deep enough to actually be useful in interviews and real engineering work.

That's the gap this repo tries to fill. Every topic is explained with intuition first (why does this concept even exist, what problem does it solve) before getting into the how. No topic is assumed to be "obvious."

## Who this is for

- College students who want to get into system design before their first job
- Freshers and early career engineers preparing for interviews
- Anyone who knows how to code but has never designed a system beyond a single script or class
- Engineers with a couple of years of experience who want to fill gaps in their fundamentals rather than jump straight to advanced distributed systems papers
- Basically, anyone who wants to learn system design properly instead of memorizing a checklist of terms to say in an interview

You don't need any prior system design background to start. Some comfort with programming and basic backend concepts (APIs, databases at a surface level) is enough.

## What's inside

The repo is split into four stages, meant to be gone through roughly in order.

### `00-prerequisites`
The absolute basics you need before "system design" as a topic even makes sense. What system design actually means, functional vs non-functional requirements, the metrics engineers argue about (latency, throughput, availability), the fundamental trade-offs every design decision runs into, and how to think in terms of APIs before writing any code.

### `01-basics`
The building blocks that every system, big or small, is made of: networking, scalability, databases, caching, messaging, reliability, and consistency. These are the pieces that get combined and recombined in every design discussion later in the repo, so this section is worth taking slow.

### `02-hld`
High level design. This is the "whiteboard interview" stage: given a problem statement, how do you approach it, design APIs and data models, pick the right components, decide how to partition and scale, handle failures, and reason about consistency and performance trade-offs. It closes with a set of canonical problems (URL shortener, feed system, chat system, distributed storage, notification system, rate limiter, payments system) worked through end to end, plus a section on how to actually communicate your design out loud in an interview.

### `03-lld`
Low level design. Where HLD asks "what components do I need," LLD asks "how do I actually write the classes and code for one of those components." This section covers OOP and SOLID principles, object modelling and relationships, UML, all the classic design patterns (creational, structural, behavioral), concurrency and thread safety, and a set of interview style LLD problems, each backed by working Java code alongside the conceptual notes.

Each subdirectory has its own README with the exact list of topics and how to navigate that section, so this file stays a map rather than a repeat of the contents.

## How to go through it

If you're starting from zero, go in order: `00` then `01` then `02` then `03`. The later sections lean on concepts from the earlier ones without re-explaining them, so skipping ahead will leave gaps.

If you already know the basics and just want interview prep, you can jump straight to `02-hld` and `03-lld`, and dip back into `00` or `01` whenever something feels shaky.

A rough way to use each topic file:
1. Read it once for the big picture, don't worry about memorizing anything.
2. Come back and read it a second time, slower, actually thinking through the reasoning and trade-offs.
3. For the HLD canonical problems and LLD interview problems, try designing the system or writing the code yourself first, then compare with what's in the repo.

System design is not something you learn by reading alone. Treat this repo as a guide for what to study and in what order, not as a substitute for actually practicing.

## A note on how this is written

Everything here is written the way I'd want it explained to me: starting from intuition, then principles, then real examples, then trade-offs. If something doesn't make sense or seems wrong, feel free to open an issue.

This repo is a living thing and gets updated as I learn more, so expect it to keep growing.