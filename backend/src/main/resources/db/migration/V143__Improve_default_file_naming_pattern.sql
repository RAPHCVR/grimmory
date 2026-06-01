UPDATE app_settings
SET val = '{authors}/<{series}/><{seriesIndex}. >{title}/{title}< - {authors}>< ({year})>'
WHERE name = 'upload_file_pattern'
  AND val = '{authors}/<{series}/><{seriesIndex}. >/{title}/{title}< - {authors}>< ({year})>';
