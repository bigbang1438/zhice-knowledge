<script setup>
defineProps({name:String,size:{type:Number,default:20}});
const paths={
  book:'M4 4h7a3 3 0 0 1 3 3v14a4 4 0 0 0-4-3H4z M20 4h-3a3 3 0 0 0-3 3v14a4 4 0 0 1 4-3h2z',
  chat:'M20 11.5a8.5 8.5 0 0 1-8.5 8.5H4l1.7-4A8.5 8.5 0 1 1 20 11.5Z M8 9h8 M8 13h5',
  file:'M14 3H5v18h14V8z M14 3v5h5 M8 12h8 M8 16h6',
  search:'M17 17l4 4 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  plus:'M12 5v14 M5 12h14',send:'m4 11 16-7-7 16-2-7z M11 13l9-9',
  arrow:'M5 12h14 M13 6l6 6-6 6',close:'m6 6 12 12 M6 18 18 6',
  upload:'M12 16V3 M7 8l5-5 5 5 M4 16v5h16v-5',
  chevron:'m9 5 7 7-7 7',logout:'M10 4H4v16h6 M10 12h11 M17 8l4 4-4 4',
  layers:'m12 3 10 5-10 5L2 8z M2 12l10 5 10-5 M2 16l10 5 10-5',
  check:'m5 12 4 4L19 6',trace:'M6 3v12a4 4 0 0 0 4 4h8 M6 7h12 M14 3l4 4-4 4 M14 15l4 4-4 4',
  refresh:'M20 8a8 8 0 1 0 1 6 M20 3v5h-5',menu:'M4 6h16 M4 12h16 M4 18h16',
  warning:'m12 3 10 18H2z M12 9v5 M12 17v1'
};
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name]||paths.file"/></svg></template>
