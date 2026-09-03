
# This script is to give unique name to the strokes in lottie with the same color
# so that we can get all strokes with same color in the code by keyPath
import json
from pathlib import Path
list = ["#96AAC7","#C9D4E0","#6C92B9","#6184A7", "#FFFFFF"]
COLOR_NAME_MAP = {
    "#96AAC7":"Color 1",
    "#C9D4E0":"Color 2",
    "#6C92B9":"Color 3",
    "#6184A7":"Color 4",
    "#FFFFFF":"#FFFFFF"
}

def rewriteShapeNameForColor(shape):
    if (shape['ty'] == 'st' or shape['ty'] == 'fl'):
        color = shape['c']['k']
        colorName = '#%02X%02X%02X' % (round(color[0] * 255),round(color[1] * 255),round(color[2] * 255))
        if (colorName not in list):
            print("Invalid color: " + colorName + " for " + str(filename))
        else:
            colorHexSet.add(colorName)
            shape['nm'] = COLOR_NAME_MAP[colorName]

def recursivelySearchStroke(shape):
    if 'ty' in shape and shape['ty'] == 'gr':
        for it in shape['it']:
            recursivelySearchStroke(it)
    else:
        rewriteShapeNameForColor(shape)
def traverseLayers(layers):
    for layer in layers:
        if "STROKES" in layer['nm']:
            if 'op' in layer:
                layer['op'] = 60
            if 'shapes' in layer:
                for shape in layer['shapes']:
                    recursivelySearchStroke(shape)
for filename in Path('.').glob("*.json"):
    file = open(filename)
    jsonContent = json.load(file)
    # change total frame fro 61 to 60
    jsonContent['op'] = 60
    print(str(filename) + " Layersize " + str(len(jsonContent['layers'])))
    # if ('LOCKSCREEN' in str(filename)):
    colorHexSet = set()
    # rename strokes according to their color, some of them are in the group
    if ('layers' in jsonContent):
        traverseLayers(jsonContent['layers'])
    if ('assets' in jsonContent):
        for asset in jsonContent['assets']:
            traverseLayers(asset['layers'])
    with open(filename, 'w') as f:
        json.dump(jsonContent, f)